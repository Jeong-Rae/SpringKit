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
import java.util.UUID

/*
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
    private val storePort: WorkflowStorePort? = null,
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

  /*
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
        persistRevisionMutation(
            current = current.data,
            operation = "comment",
            idempotencyKey =
                "${request.pullRequestId}:${request.reviewRevisionId}:${request.comment.id}",
            action = { reviewPort.comment(markedRequest) },
            reviewRevision = { it.reviewRevision },
            valid = {
              isValidRevisionChange(current.data, it.reviewRevision) &&
                  it.reviewRevision.threads.any { thread -> thread.id == it.threadId }
            },
            invalidMessage = "review provider returned an invalid comment revision",
        )
      }
    }
  }

  fun reply(request: ReplyReviewThreadRequest): WorkflowResult<ReplyReviewThreadResponse> =
      when (val current = reviewPort.get(GetReviewRequest(request.pullRequestId))) {
        is PortResult.Failure -> failureFrom(current)
        is PortResult.Success -> {
          val pullRequest = current.value.pullRequest
          if (pullRequest.reviewRevision.id != request.reviewRevisionId) {
            val markedRequest = request.copy(comment = request.comment.withAgentMark())
            when (val recovered = reviewPort.recoverReply(markedRequest)) {
              is PortResult.Failure -> failureFrom(recovered)
              is PortResult.Success -> {
                val response = recovered.value
                if (response == null) {
                  WorkflowResult.Failure(
                      requireCurrentReview(pullRequest, request.reviewRevisionId)!!,
                  )
                } else {
                  persistRecoveredReply(request, response)
                }
              }
            }
          } else {
            val thread =
                pullRequest.reviewRevision.threads.firstOrNull { it.id == request.threadId }
            when {
              thread == null ->
                  failure(
                      FailureCode.THREAD_NOT_FOUND,
                      "review thread does not belong to the revision",
                  )
              !thread.isOpen ->
                  failure(
                      FailureCode.STATE_CONFLICT,
                      "a resolved review thread cannot receive a reply",
                  )
              else -> {
                val markedRequest = request.copy(comment = request.comment.withAgentMark())
                persistRevisionMutation(
                    current = pullRequest,
                    operation = "reply",
                    idempotencyKey =
                        "${request.pullRequestId}:${request.reviewRevisionId}:${request.comment.id}",
                    action = { reviewPort.reply(markedRequest) },
                    reviewRevision = { it.reviewRevision },
                    valid = {
                      isValidRevisionChange(pullRequest, it.reviewRevision) &&
                          it.reviewRevision.threads.any { item -> item.id == thread.id }
                    },
                    invalidMessage = "review provider returned an invalid reply revision",
                )
              }
            }
          }
        }
      }

  /*
   * 이미 게시된 원격 답글의 revision만 저장하며 Store 실패 시 원격 답글을 보상하지 않습니다.
   */
  private fun persistRecoveredReply(
      request: ReplyReviewThreadRequest,
      response: ReplyReviewThreadResponse,
  ): WorkflowResult<ReplyReviewThreadResponse> {
    val store = storePort ?: return WorkflowResult.Success(response)
    val idempotencyKey =
        "${request.pullRequestId}:${request.reviewRevisionId}:${request.comment.id}"
    val snapshot =
        when (val result = store.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          is PortResult.Success -> result.value.snapshot
        }
    val stored = snapshot.pullRequests.firstOrNull { it.id == request.pullRequestId }
    if (stored == null) {
      return failure(
          FailureCode.REVIEW_NOT_FOUND,
          "pull request was not found",
          request.pullRequestId,
      )
    }
    if (stored.reviewRevision.id != request.reviewRevisionId)
        return staleReview(request.pullRequestId)
    if (!isValidRevisionChange(stored, response.reviewRevision)) {
      return invalidProvider(response.change, "review provider returned an invalid reply revision")
    }
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = "review-reply-recovery-${UUID.randomUUID()}",
            expectedRevision = snapshot.revision,
            idempotencyKey = idempotencyKey,
        )
    return WorkflowTransaction(store, compensationGateway()).execute(transactionRequest) {
        transactionSnapshot ->
      val transactionStored =
          transactionSnapshot.pullRequests.firstOrNull { it.id == request.pullRequestId }
      when {
        transactionStored == null ->
            failurePort(
                FailureCode.REVIEW_NOT_FOUND,
                "pull request was not found",
                request.pullRequestId,
            )
        transactionStored.reviewRevision.id != request.reviewRevisionId ->
            failurePort(
                FailureCode.STALE_REVISION,
                "review revision is stale",
                request.pullRequestId,
            )
        else ->
            PortResult.Success(
                TransactionMutation(
                    data = response,
                    snapshot =
                        transactionSnapshot.replacePullRequest(
                            transactionStored.copy(reviewRevision = response.reviewRevision)
                        ),
                    changes = emptyList(),
                )
            )
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
          persistRevisionMutation(
              current = current,
              operation = "resolve",
              idempotencyKey =
                  "${request.pullRequestId}:${request.reviewRevisionId}:${request.threadId}",
              action = { reviewPort.resolve(request) },
              reviewRevision = { it.reviewRevision },
              valid = {
                isValidRevisionChange(current, it.reviewRevision) &&
                    it.reviewRevision.threads.any { item ->
                      item.id == thread.id &&
                          item.state == io.springkit.workflow.domain.ThreadState.RESOLVED
                    }
              },
              invalidMessage = "review provider returned an unresolved thread",
          )
        }
      }

  private fun validateOpen(request: OpenReviewRequest): FailureData? {
    if (request.subTaskId.isBlank() || request.title.isBlank() || request.body.isBlank()) {
      return FailureData(FailureCode.INVALID_ARGUMENT, "review identity and body must not be blank")
    }
    if (request.base.isBlank() || request.branch.isBlank()) {
      return FailureData(FailureCode.INVALID_ARGUMENT, "review base and branch must not be blank")
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
        pullRequest.reviewRevision.id == request.reviewRevision.id &&
        pullRequest.changeRevision.id == request.changeRevision.id
  }

  private fun isValidRevisionChange(
      before: PullRequest,
      after: io.springkit.workflow.domain.ReviewRevision,
  ): Boolean = before.reviewRevision.id != after.id

  /*
   * 외부 Review 변경을 검증하고 최신 Review revision을 Store에 원자적으로 반영합니다.
   */
  private fun <T : ChangeResponse> persistRevisionMutation(
      current: PullRequest,
      operation: String,
      idempotencyKey: String,
      action: () -> PortResult<T>,
      reviewRevision: (T) -> io.springkit.workflow.domain.ReviewRevision,
      valid: (T) -> Boolean,
      invalidMessage: String,
  ): WorkflowResult<T> {
    val store = storePort
    if (store == null) {
      return completeMutation(action(), reviewRevision, valid, invalidMessage)
    }
    val snapshot =
        when (val result = store.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          is PortResult.Success -> result.value.snapshot
        }
    val stored = snapshot.pullRequests.firstOrNull { it.id == current.id }
    if (stored == null) {
      return failure(FailureCode.REVIEW_NOT_FOUND, "pull request was not found", current.id)
    }
    if (stored.reviewRevision.id != current.reviewRevision.id) {
      return staleReview(current.id)
    }
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = "review-$operation-$idempotencyKey",
            expectedRevision = snapshot.revision,
            idempotencyKey = idempotencyKey,
        )
    return WorkflowTransaction(store, compensationGateway()).execute(transactionRequest) {
        transactionSnapshot ->
      val transactionStored = transactionSnapshot.pullRequests.firstOrNull { it.id == current.id }
      if (transactionStored == null) {
        failurePort(
            FailureCode.REVIEW_NOT_FOUND,
            "pull request was not found",
            current.id,
        )
      } else if (transactionStored.reviewRevision.id != current.reviewRevision.id) {
        failurePort(
            FailureCode.STALE_REVISION,
            "review revision is stale",
            current.id,
        )
      } else {
        when (val result = action()) {
          is PortResult.Failure -> result
          is PortResult.Success -> {
            val nextRevision = reviewRevision(result.value)
            if (!valid(result.value)) {
              PortResult.Failure(
                  PortError(
                      FailureCode.INVARIANT_VIOLATION.name,
                      invalidMessage,
                      target = current.id,
                  ),
                  result.value.change,
              )
            } else {
              val nextPullRequest = transactionStored.copy(reviewRevision = nextRevision)
              PortResult.Success(
                  TransactionMutation(
                      data = result.value,
                      snapshot = transactionSnapshot.replacePullRequest(nextPullRequest),
                      changes = listOf(result.value.change),
                  )
              )
            }
          }
        }
      }
    }
  }

  private fun <T : ChangeResponse> completeMutation(
      result: PortResult<T>,
      reviewRevision: (T) -> io.springkit.workflow.domain.ReviewRevision,
      valid: (T) -> Boolean,
      invalidMessage: String,
  ): WorkflowResult<T> =
      when (result) {
        is PortResult.Failure -> failureFrom(result)
        is PortResult.Success ->
            if (valid(result.value) && reviewRevision(result.value).id.isNotBlank())
                WorkflowResult.Success(result.value)
            else invalidProvider(result.value.change, invalidMessage)
      }

  private fun staleReview(target: String): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code = FailureCode.STALE_REVISION,
              message = "review revision is stale",
              blockedBy =
                  listOf(
                      io.springkit.workflow.domain.BlockedBy(
                          code = "STALE_REVISION",
                          message = "the review changed after it was read",
                          target = target,
                      )
                  ),
          )
      )

  private fun <T> failurePort(
      code: FailureCode,
      message: String,
      target: String,
  ): PortResult<T> = PortResult.Failure(PortError(code.name, message, target = target))

  private fun WorkflowStoreSnapshot.replacePullRequest(value: PullRequest): WorkflowStoreSnapshot =
      copy(pullRequests = pullRequests.filterNot { it.id == value.id } + value)

  private fun compensationGateway(): CompensationPort =
      compensationPort
          ?: object : CompensationPort {
            override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
                PortResult.Success(CompensateResponse(request.change))
          }

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

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 생성 요청 별칭입니다.
 */
typealias ReviewOpenUseCaseRequest = OpenReviewRequest

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 조회 요청 별칭입니다.
 */
typealias ReviewShowUseCaseRequest = GetReviewRequest

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 갱신 요청 별칭입니다.
 */
typealias ReviewUpdateUseCaseRequest = UpdateReviewRequest

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 댓글 요청 별칭입니다.
 */
typealias ReviewCommentUseCaseRequest = AddReviewCommentRequest

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 답글 요청 별칭입니다.
 */
typealias ReviewReplyUseCaseRequest = ReplyReviewThreadRequest

/*
 * 인바운드 어댑터가 별도 공급자 요청 모델 없이 사용하는 리뷰 스레드 해결 요청 별칭입니다.
 */
typealias ReviewResolveUseCaseRequest = ResolveReviewThreadRequest
