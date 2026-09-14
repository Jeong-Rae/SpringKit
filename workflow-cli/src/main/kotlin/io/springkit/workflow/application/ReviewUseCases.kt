package io.springkit.workflow.application

import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.ReviewRevisionId
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.ThreadId
import io.springkit.workflow.domain.WorkflowResult

/**
 * 리뷰 생명주기를 처리하는 애플리케이션 경계입니다.
 *
 * 공급자 중립적인 포트 계약만 주고받습니다. 외부 식별자와 전송 방식은 어댑터가 소유합니다. 이 클래스는 낙관적 revision 검사와 리뷰 revision 및 코드 변경
 * revision을 구분하는 규칙을 소유합니다.
 */
class ReviewUseCases(
    private val reviewPort: ReviewPort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
) {
  fun open(request: OpenReviewRequest): WorkflowResult<OpenReviewResponse> {
    val invalid = validateOpen(request)
    if (invalid != null) return WorkflowResult.Failure(invalid)
    return when (val result = reviewPort.open(request)) {
      is PortResult.Failure -> failureFrom(result)
      is PortResult.Success ->
          if (isValidOpenResponse(request, result.value)) WorkflowResult.Success(result.value)
          else
              invalidProvider(
                  result.value.change,
                  "review provider returned an invalid open response",
              )
    }
  }

  fun show(request: GetReviewRequest): WorkflowResult<GetReviewResponse> =
      when (val result = reviewPort.get(request)) {
        is PortResult.Failure -> failureFrom(result)
        is PortResult.Success ->
            if (result.value.pullRequest.id == request.pullRequestId) {
              WorkflowResult.Success(result.value)
            } else {
              failure(
                  FailureCode.INVARIANT_VIOLATION,
                  "review provider returned a different pull request",
              )
            }
      }

  /**
   * 문서만 변경하면 CI, AI 리뷰, 승인과 코드 변경 revision을 유지합니다.
   *
   * 코드가 변경되면 새 변경 revision을 부여하고 이전 코드를 가리키는 검사와 승인을 무효화합니다.
   */
  fun update(request: UpdateReviewRequest): WorkflowResult<UpdateReviewResponse> {
    if (request.body?.isBlank() == true) {
      return failure(FailureCode.INVALID_ARGUMENT, "review body must not be blank")
    }
    if (request.body == null && request.changeRevision == null && request.reviewRevision == null) {
      return failure(FailureCode.INVALID_ARGUMENT, "review update must change the body or revision")
    }

    return when (val current = reviewPort.get(GetReviewRequest(request.pullRequestId))) {
      is PortResult.Failure -> failureFrom(current)
      is PortResult.Success -> {
        val pullRequest = current.value.pullRequest
        val stale = requireCurrentReview(pullRequest, request.expectedReviewRevisionId)
        if (stale != null) {
          WorkflowResult.Failure(stale)
        } else {
          when (val updated = reviewPort.update(request)) {
            is PortResult.Failure -> failureFrom(updated)
            is PortResult.Success -> {
              val response = normalizeUpdate(pullRequest, request, updated.value)
              if (response == null) {
                invalidProvider(
                    updated.value.change,
                    "review provider returned an invalid revision transition",
                )
              } else {
                WorkflowResult.Success(response)
              }
            }
          }
        }
      }
    }
  }

  fun comment(request: AddReviewCommentRequest): WorkflowResult<AddReviewCommentResponse> {
    if (request.author != request.comment.author) {
      return failure(FailureCode.INVALID_ARGUMENT, "comment author must match the review actor")
    }
    return when (val current = currentReview(request.pullRequestId, request.reviewRevisionId)) {
      is WorkflowResult.Failure -> current
      is WorkflowResult.Success -> {
        val markedRequest = request.copy(comment = request.comment.withAgentMark())
        when (val result = reviewPort.comment(markedRequest)) {
          is PortResult.Failure -> failureFrom(result)
          is PortResult.Success ->
              if (
                  isValidRevisionChange(current.data, result.value.reviewRevision) &&
                      result.value.reviewRevision.threads.any { it.id == result.value.threadId }
              ) {
                WorkflowResult.Success(result.value)
              } else {
                invalidProvider(
                    result.value.change,
                    "review provider returned an invalid comment revision",
                )
              }
        }
      }
    }
  }

  fun reply(request: ReplyReviewThreadRequest): WorkflowResult<ReplyReviewThreadResponse> =
      withThread(request.pullRequestId, request.reviewRevisionId, request.threadId) {
          current,
          thread ->
        if (!thread.isOpen) {
          failure(FailureCode.STATE_CONFLICT, "a resolved review thread cannot receive a reply")
        } else {
          val markedRequest = request.copy(comment = request.comment.withAgentMark())
          when (val result = reviewPort.reply(markedRequest)) {
            is PortResult.Failure -> failureFrom(result)
            is PortResult.Success ->
                if (
                    isValidRevisionChange(current, result.value.reviewRevision) &&
                        result.value.reviewRevision.threads.any { it.id == thread.id }
                ) {
                  WorkflowResult.Success(result.value)
                } else {
                  invalidProvider(
                      result.value.change,
                      "review provider returned an invalid reply revision",
                  )
                }
          }
        }
      }

  fun resolve(request: ResolveReviewThreadRequest): WorkflowResult<ResolveReviewThreadResponse> =
      withThread(request.pullRequestId, request.reviewRevisionId, request.threadId) {
          current,
          thread ->
        if (!thread.isOpen) {
          failure(FailureCode.STATE_CONFLICT, "review thread is already resolved")
        } else if (thread.requiresHumanResolution && !request.actor.isHuman) {
          failure(
              FailureCode.HUMAN_REQUIRED,
              "a review thread containing a human comment requires a human resolver",
              target = thread.id,
              blockedByCode = "HUMAN_REVIEW_REQUIRED",
          )
        } else {
          when (val result = reviewPort.resolve(request)) {
            is PortResult.Failure -> failureFrom(result)
            is PortResult.Success ->
                if (
                    isValidRevisionChange(current, result.value.reviewRevision) &&
                        result.value.reviewRevision.threads.any {
                          it.id == thread.id &&
                              it.state == io.springkit.workflow.domain.ThreadState.RESOLVED
                        }
                ) {
                  WorkflowResult.Success(result.value)
                } else {
                  invalidProvider(
                      result.value.change,
                      "review provider returned an unresolved thread",
                  )
                }
          }
        }
      }

  private fun validateOpen(request: OpenReviewRequest): FailureData? {
    if (request.subTaskId.isBlank() || request.title.isBlank() || request.body.isBlank()) {
      return FailureData(FailureCode.INVALID_ARGUMENT, "review identity and body must not be blank")
    }
    if (request.base.isBlank() || request.branch.isBlank()) {
      return FailureData(FailureCode.INVALID_ARGUMENT, "review base and branch must not be blank")
    }
    if (
        request.exposure == io.springkit.workflow.domain.Exposure.FEATURE_FLAG &&
            request.featureFlagId.isNullOrBlank()
    ) {
      return FailureData(
          FailureCode.INVALID_ARGUMENT,
          "feature-flag exposure requires a feature flag id",
      )
    }
    if (
        request.exposure == io.springkit.workflow.domain.Exposure.UNCHANGED &&
            request.featureFlagId != null
    ) {
      return FailureData(
          FailureCode.INVALID_ARGUMENT,
          "unchanged exposure must not have a feature flag id",
      )
    }
    return null
  }

  private fun currentReview(
      pullRequestId: String,
      expectedReviewRevisionId: ReviewRevisionId,
  ): WorkflowResult<PullRequest> =
      when (val result = reviewPort.get(GetReviewRequest(pullRequestId))) {
        is PortResult.Failure -> failureFrom(result)
        is PortResult.Success -> {
          val stale = requireCurrentReview(result.value.pullRequest, expectedReviewRevisionId)
          if (stale != null) WorkflowResult.Failure(stale)
          else WorkflowResult.Success(result.value.pullRequest)
        }
      }

  private fun <T> withThread(
      pullRequestId: String,
      expectedReviewRevisionId: ReviewRevisionId,
      threadId: ThreadId,
      action: (PullRequest, ReviewThread) -> WorkflowResult<T>,
  ): WorkflowResult<T> =
      when (val current = currentReview(pullRequestId, expectedReviewRevisionId)) {
        is WorkflowResult.Failure -> current
        is WorkflowResult.Success -> {
          val thread = current.data.reviewRevision.threads.firstOrNull { it.id == threadId }
          if (thread == null) {
            failure(FailureCode.THREAD_NOT_FOUND, "review thread does not belong to the revision")
          } else {
            action(current.data, thread)
          }
        }
      }

  private fun isValidOpenResponse(
      request: OpenReviewRequest,
      response: OpenReviewResponse,
  ): Boolean {
    val pullRequest = response.pullRequest
    return pullRequest.subTaskId == request.subTaskId &&
        pullRequest.title == request.title &&
        pullRequest.body == request.body &&
        pullRequest.base == request.base &&
        pullRequest.state == io.springkit.workflow.domain.PullRequestState.DRAFT &&
        pullRequest.risk == request.risk &&
        pullRequest.exposure == request.exposure &&
        pullRequest.featureFlagId == request.featureFlagId &&
        pullRequest.reviewRevision.id == request.reviewRevision.id &&
        pullRequest.changeRevision.id == request.changeRevision.id
  }

  private fun isValidRevisionChange(
      before: PullRequest,
      after: io.springkit.workflow.domain.ReviewRevision,
  ): Boolean = before.reviewRevision.id != after.id

  private fun requireCurrentReview(
      pullRequest: PullRequest,
      expectedReviewRevisionId: ReviewRevisionId,
  ): FailureData? =
      if (pullRequest.reviewRevision.id == expectedReviewRevisionId) null
      else
          FailureData(
              code = FailureCode.STALE_REVISION,
              message = "review revision is stale",
              blockedBy =
                  listOf(
                      io.springkit.workflow.domain.BlockedBy(
                          code = "STALE_REVISION",
                          message = "the review changed after it was read",
                          target = pullRequest.reviewRevision.id,
                      ),
                  ),
          )

  private fun normalizeUpdate(
      before: PullRequest,
      request: UpdateReviewRequest,
      response: UpdateReviewResponse,
  ): UpdateReviewResponse? {
    val after = response.pullRequest
    if (after.id != before.id || response.codeChanged != (request.changeRevision != null)) {
      return null
    }
    if (request.body != null && after.body != request.body) return null
    if (request.body == null && after.body != before.body) return null
    if (request.reviewRevision != null && after.reviewRevision.id != request.reviewRevision.id) {
      return null
    }
    if (after.reviewRevision.id == before.reviewRevision.id) return null
    if (response.codeChanged) {
      if (
          request.changeRevision == null ||
              after.changeRevision.id == before.changeRevision.id ||
              after.changeRevision.id != request.changeRevision.id
      ) {
        return null
      }
      return response.copy(
          pullRequest =
              after.copy(
                  approval = null,
                  ci = CiStatus.PENDING,
                  aiReview = AiReviewStatus.PENDING,
              ),
      )
    }

    if (after.changeRevision.id != before.changeRevision.id) return null
    return response.copy(
        pullRequest =
            after.copy(
                changeRevision = before.changeRevision,
                approval = before.approval,
                ci = before.ci,
                aiReview = before.aiReview,
            ),
    )
  }

  private fun io.springkit.workflow.domain.ReviewComment.withAgentMark() =
      if (
          author.kind != io.springkit.workflow.domain.ActorKind.AGENT || body.startsWith("[Agent]")
      ) {
        this
      } else {
        copy(body = "[Agent] $body")
      }

  private fun <T> failureFrom(result: PortResult.Failure): WorkflowResult<T> {
    compensate(result.change, "review operation failed: ${result.error.code}")
    return failure(
        failureCode(result.error.code),
        result.error.message,
        result.error.target,
    )
  }

  private fun invalidProvider(change: ChangeReceipt, message: String): WorkflowResult.Failure {
    compensate(change, "review provider invariant violated")
    return WorkflowResult.Failure(
        FailureData(
            code = FailureCode.INVARIANT_VIOLATION,
            message = message,
        ),
    )
  }

  private fun compensate(change: ChangeReceipt?, reason: String) {
    if (change != null && change.status != ChangeStatus.COMPENSATED) {
      compensationPort?.compensate(CompensateRequest(change, reason))
    }
  }

  private fun failureCode(code: String): FailureCode =
      runCatching { FailureCode.valueOf(code.uppercase()) }
          .getOrDefault(
              when (code.uppercase()) {
                "CONFLICT",
                "REVISION_CONFLICT" -> FailureCode.STATE_CONFLICT
                "FORBIDDEN",
                "UNAUTHORIZED" -> FailureCode.HUMAN_REQUIRED
                else -> FailureCode.STATE_CONFLICT
              },
          )

  private fun <T> failure(
      code: FailureCode,
      message: String,
      target: String? = null,
      blockedByCode: String = code.name,
  ): WorkflowResult<T> =
      WorkflowResult.Failure(
          FailureData(
              code = code,
              message = message,
              blockedBy =
                  if (target == null) emptyList()
                  else
                      listOf(
                          io.springkit.workflow.domain.BlockedBy(blockedByCode, message, target)
                      ),
          ),
      )
}

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 생성 요청 별칭입니다. */
typealias ReviewOpenUseCaseRequest = OpenReviewRequest

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 조회 요청 별칭입니다. */
typealias ReviewShowUseCaseRequest = GetReviewRequest

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 갱신 요청 별칭입니다. */
typealias ReviewUpdateUseCaseRequest = UpdateReviewRequest

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 댓글 요청 별칭입니다. */
typealias ReviewCommentUseCaseRequest = AddReviewCommentRequest

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 답글 요청 별칭입니다. */
typealias ReviewReplyUseCaseRequest = ReplyReviewThreadRequest

/** 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 스레드 해결 요청 별칭입니다. */
typealias ReviewResolveUseCaseRequest = ResolveReviewThreadRequest
