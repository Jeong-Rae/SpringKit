package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.ChangeRevisionId
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.DiffIdentity
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.GateRecorded
import io.springkit.workflow.domain.GateType
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewRevisionId
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkflowRules
import io.springkit.workflow.domain.toSubTaskState

/*
 * 사람이 리뷰 Gate를 기록할 때 사용하는 Ready 요청입니다.
 */
data class ReadyGateRequest(
    val subTaskId: SubTaskId,
    val reviewRevisionId: ReviewRevisionId,
    val requestId: String = "gate-ready-" + subTaskId + "-" + reviewRevisionId,
    val expectedStoreRevision: String? = null,
)

/*
 * 사람이 코드 변경을 승인할 때 사용하는 Approve 요청입니다.
 */
data class ApproveGateRequest(
    val subTaskId: SubTaskId,
    val changeRevisionId: ChangeRevisionId,
    val diffIdentity: DiffIdentity? = null,
    val requestId: String = "gate-approve-" + subTaskId + "-" + changeRevisionId,
    val expectedStoreRevision: String? = null,
)

/*
 * Ready Gate 기록 결과와 저장된 리뷰 상태입니다.
 */
data class ReadyGateResponse(
    val subTask: SubTask,
    val pullRequest: PullRequest,
    val audit: AuditEntry,
)

/*
 * Approve Gate 기록과 Merge Queue 등록 결과입니다.
 */
data class ApproveGateResponse(
    val subTask: SubTask,
    val pullRequest: PullRequest,
    val approval: Approval,
    val mergeQueue: MergeQueueEntry,
    val audit: AuditEntry,
)

/*
 * 인바운드 Adapter가 사용할 수 있는 Ready 요청 이름입니다.
 */
typealias ReviewReadyUseCaseRequest = ReadyGateRequest

/*
 * 인바운드 Adapter가 사용할 수 있는 Approve 요청 이름입니다.
 */
typealias ReviewApproveUseCaseRequest = ApproveGateRequest

/*
 * 인바운드 Adapter가 사용할 수 있는 Ready 결과 이름입니다.
 */
typealias ReviewReadyUseCaseResponse = ReadyGateResponse

/*
 * 인바운드 Adapter가 사용할 수 있는 Approve 결과 이름입니다.
 */
typealias ReviewApproveUseCaseResponse = ApproveGateResponse

/*
 * 사람의 Ready와 Approve 결정을 조정하는 Application 유스케이스입니다.
 *
 * 현재 사람 주체와 권한을 Identity Port에서 확인하고, Workflow Store의 일관된 snapshot을 기준으로 Gate 조건을 검증합니다. 외부 리뷰와
 * Merge Queue 변경은 WorkflowTransaction의 보상 계약으로 감싸며, 성공한 상태와 감사 기록은 하나의 저장소 쓰기로 반영합니다.
 */
class ReviewGateUseCases(
    private val identityPort: IdentityPort,
    private val reviewPort: ReviewPort,
    private val storePort: WorkflowStorePort,
    private val mergeQueuePort: MergeQueuePort? = null,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
) {
  /*
   * 기존 Gate 유스케이스와 같은 포트 순서로 Application 경계를 생성합니다.
   */
  constructor(
      orderedStorePort: WorkflowStorePort,
      orderedReviewPort: ReviewPort,
      orderedMergeQueuePort: MergeQueuePort?,
      orderedIdentityPort: IdentityPort,
      orderedIdPort: IdPort? = null,
      orderedClockPort: ClockPort? = null,
      orderedCompensationPort: CompensationPort? = null,
  ) : this(
      orderedIdentityPort,
      orderedReviewPort,
      orderedStorePort,
      orderedMergeQueuePort,
      orderedIdPort,
      orderedClockPort,
      orderedCompensationPort,
  )

  /*
   * 저장소의 현재 review revision이 요청과 일치하면 PR을 Ready로 전환합니다.
   */
  fun ready(request: ReadyGateRequest): WorkflowResult<ReadyGateResponse> {
    val invalid = validateReadyRequest(request)
    if (invalid != null) return WorkflowResult.Failure(invalid)

    return withHuman(
        target = request.subTaskId,
        capability = Capability.READY,
        requestId = request.requestId,
        expectedStoreRevision = request.expectedStoreRevision,
    ) { actor, transaction ->
      when (val result = findTarget(transaction.snapshot, request.subTaskId)) {
        is PortResult.Failure -> result
        is PortResult.Success -> {
          val target = result.value
          val stateFailure = WorkflowRules.humanGateCondition(GateType.READY, actor, target.pr)
          if (stateFailure != null) {
            failure(stateFailure)
          } else if (target.pr.reviewRevision.id != request.reviewRevisionId) {
            failure(
                FailureCode.STALE_REVISION,
                "review revision is stale",
                request.subTaskId,
            )
          } else {
            readyReview(actor, request, transaction, target)
          }
        }
      }
    }
  }

  /*
   * Ready Gate의 명시적 실행 이름입니다.
   */
  fun recordReady(request: ReadyGateRequest): WorkflowResult<ReadyGateResponse> = ready(request)

  /*
   * Ready 요청을 인바운드 Adapter의 공통 실행 이름으로 처리합니다.
   */
  fun execute(request: ReadyGateRequest): WorkflowResult<ReadyGateResponse> = ready(request)

  /*
   * Ready 요청을 인바운드 Adapter의 공통 진입점으로 처리합니다.
   */
  fun handle(request: ReadyGateRequest): WorkflowResult<ReadyGateResponse> = ready(request)

  /*
   * 현재 코드 변경과 필수 조건을 확인한 뒤 Approve와 Queue 등록을 수행합니다.
   */
  fun approve(request: ApproveGateRequest): WorkflowResult<ApproveGateResponse> {
    val invalid = validateApproveRequest(request)
    if (invalid != null) return WorkflowResult.Failure(invalid)

    return withHuman(
        target = request.subTaskId,
        capability = Capability.APPROVE,
        requestId = request.requestId,
        expectedStoreRevision = request.expectedStoreRevision,
    ) { actor, transaction ->
      when (val result = findTarget(transaction.snapshot, request.subTaskId)) {
        is PortResult.Failure -> result
        is PortResult.Success -> {
          val target = result.value
          validateApprovalConditions(transaction.snapshot, target, actor, request)?.let(::failure)
              ?: approveReview(actor, request, transaction, target)
        }
      }
    }
  }

  /*
   * Approve Gate의 명시적 실행 이름입니다.
   */
  fun recordApproval(request: ApproveGateRequest): WorkflowResult<ApproveGateResponse> =
      approve(request)

  /*
   * Approve 요청을 인바운드 Adapter의 공통 실행 이름으로 처리합니다.
   */
  fun execute(request: ApproveGateRequest): WorkflowResult<ApproveGateResponse> = approve(request)

  /*
   * Approve 요청을 인바운드 Adapter의 공통 진입점으로 처리합니다.
   */
  fun handle(request: ApproveGateRequest): WorkflowResult<ApproveGateResponse> = approve(request)

  private fun readyReview(
      actor: Actor,
      request: ReadyGateRequest,
      transaction: GateTransaction,
      target: ReviewTarget,
  ): PortResult<TransactionMutation<ReadyGateResponse>> {
    val result =
        reviewPort.ready(
            ReadyReviewRequest(
                pullRequestId = target.pr.id,
                reviewRevisionId = request.reviewRevisionId,
                actor = actor,
            ),
        )
    return when (result) {
      is PortResult.Failure -> result
      is PortResult.Success -> {
        if (!validReadyResponse(target.pr, request, result.value.pullRequest)) {
          PortResult.Failure(
              PortError(
                  FailureCode.INVARIANT_VIOLATION.name,
                  "review provider returned an invalid ready response",
              ),
              result.value.change,
          )
        } else {
          val occurredAt = now(request.requestId + ":ready")
          val audit =
              audit(
                  requestId = request.requestId,
                  gate = GateType.READY,
                  actor = actor,
                  targetId = target.subTask.id,
                  reviewRevisionId = result.value.pullRequest.reviewRevision.id,
                  occurredAt = occurredAt,
              )
          val storedPullRequest = result.value.pullRequest
          val storedSubTask =
              target.subTask.copy(
                  state = storedPullRequest.state.toSubTaskState(),
                  pullRequestId = storedPullRequest.id,
              )
          val storedSnapshot =
              transaction.snapshot.withReviewGate(
                  subTask = storedSubTask,
                  pullRequest = storedPullRequest,
                  event = GateRecorded(target.subTask.id, GateType.READY, actor, occurredAt),
                  audit = audit,
              )
          PortResult.Success(
              TransactionMutation(
                  ReadyGateResponse(storedSubTask, storedPullRequest, audit),
                  storedSnapshot,
                  changes = listOf(result.value.change),
              ),
          )
        }
      }
    }
  }

  private fun approveReview(
      actor: Actor,
      request: ApproveGateRequest,
      transaction: GateTransaction,
      target: ReviewTarget,
  ): PortResult<TransactionMutation<ApproveGateResponse>> {
    val requestedDiff = request.diffIdentity ?: target.pr.changeRevision.diff.identity
    val approvalResult =
        reviewPort.approve(
            ApproveReviewRequest(
                pullRequestId = target.pr.id,
                changeRevisionId = request.changeRevisionId,
                diffIdentity = requestedDiff,
                actor = actor,
            ),
        )
    return when (approvalResult) {
      is PortResult.Failure -> approvalResult
      is PortResult.Success -> {
        val response = approvalResult.value
        if (!validApprovalResponse(target.pr, request, requestedDiff, actor, response)) {
          PortResult.Failure(
              PortError(
                  FailureCode.INVARIANT_VIOLATION.name,
                  "review provider returned an invalid approval response",
              ),
              response.change,
          )
        } else {
          val changes = mutableListOf(response.change)
          if (!canEnterMergeQueue(transaction.snapshot, target, response)) {
            return compensatedFailure(
                PortResult.Failure(
                    PortError(
                        FailureCode.MERGE_QUEUE_FAILED.name,
                        "merge queue conditions are no longer satisfied",
                        target = target.subTask.id,
                    ),
                ),
                changes,
            )
          }
          val queuePort = mergeQueuePort
          if (queuePort == null) {
            compensatedFailure(
                PortResult.Failure(
                    PortError(
                        FailureCode.MERGE_QUEUE_FAILED.name,
                        "merge queue port is not configured",
                        target = target.subTask.id,
                    ),
                ),
                changes,
            )
          } else {
            when (
                val queueResult =
                    queuePort.enqueue(
                        EnqueueMergeRequest(
                            subTaskId = target.subTask.id,
                            pullRequestId = target.pr.id,
                            changeRevisionId = request.changeRevisionId,
                            expectedRevision = transaction.snapshot.revision,
                        ),
                    )
            ) {
              is PortResult.Failure -> compensatedFailure(queueResult, changes)
              is PortResult.Success -> {
                val entry = queueResult.value.entry
                changes += queueResult.value.change
                if (!validQueueResponse(target, request, entry, transaction.snapshot)) {
                  compensatedFailure(
                      PortResult.Failure(
                          PortError(
                              FailureCode.INVARIANT_VIOLATION.name,
                              "merge queue provider returned an invalid queue entry",
                          ),
                      ),
                      changes,
                  )
                } else {
                  val occurredAt = now(request.requestId + ":approve")
                  val audit =
                      audit(
                          requestId = request.requestId,
                          gate = GateType.APPROVE,
                          actor = actor,
                          targetId = target.subTask.id,
                          reviewRevisionId = response.pullRequest.reviewRevision.id,
                          changeRevisionId = response.approval.changeRevisionId,
                          occurredAt = occurredAt,
                      )
                  val queuedPullRequest =
                      response.pullRequest.copy(
                          state = PullRequestState.QUEUED,
                          approval = response.approval,
                      )
                  val queuedSubTask =
                      target.subTask.copy(
                          state = SubTaskState.QUEUED,
                          pullRequestId = queuedPullRequest.id,
                      )
                  val storedSnapshot =
                      transaction.snapshot.withReviewGate(
                          subTask = queuedSubTask,
                          pullRequest = queuedPullRequest,
                          mergeQueue = entry,
                          event =
                              GateRecorded(target.subTask.id, GateType.APPROVE, actor, occurredAt),
                          audit = audit,
                      )
                  PortResult.Success(
                      TransactionMutation(
                          ApproveGateResponse(
                              queuedSubTask,
                              queuedPullRequest,
                              response.approval,
                              entry,
                              audit,
                          ),
                          storedSnapshot,
                          changes,
                      ),
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  private fun validateApprovalConditions(
      snapshot: WorkflowStoreSnapshot,
      target: ReviewTarget,
      actor: Actor,
      request: ApproveGateRequest,
  ): FailureData? {
    val stateFailure = WorkflowRules.humanGateCondition(GateType.APPROVE, actor, target.pr)
    if (stateFailure != null) return stateFailure
    if (target.pr.changeRevision.id != request.changeRevisionId) {
      return failureData(
          FailureCode.STALE_REVISION,
          "change revision is stale",
          request.subTaskId,
      )
    }
    val currentDiff = target.pr.changeRevision.diff.identity
    if (request.diffIdentity != null && request.diffIdentity != currentDiff) {
      return failureData(
          FailureCode.STALE_DIFF_IDENTITY,
          "approval diff identity is stale",
          request.subTaskId,
      )
    }
    if (target.pr.ci != CiStatus.PASSED) {
      return failureData(
          FailureCode.CI_NOT_PASSED,
          "required pull request CI has not passed",
          request.subTaskId,
      )
    }
    val checkSummary = snapshot.checks[target.subTask.id]
    if (checkSummary != null && !checkSummary.passed) {
      return failureData(
          FailureCode.CHECK_NOT_PASSED,
          "required checks have not passed",
          request.subTaskId,
      )
    }
    val openThread =
        WorkflowRules.openRequiredThreads(target.pr.reviewRevision.threads).firstOrNull()
    if (openThread != null) {
      return failureData(
          FailureCode.REQUIRED_THREAD_OPEN,
          "an unresolved required review thread remains",
          openThread.id,
      )
    }
    val dependency = target.subTask.requires
    if (dependency != null) {
      val parent = snapshot.subTasks.firstOrNull { it.id == dependency }
      if (parent == null) {
        return failureData(
            FailureCode.DEPENDENCY_NOT_FOUND,
            "direct dependency was not found",
            dependency,
        )
      }
      val integrated =
          parent.state == SubTaskState.MERGED ||
              snapshot.integrations.any {
                it.subTaskId == dependency && it.state == IntegrationState.MERGED
              }
      if (!integrated) {
        return failureData(
            FailureCode.DEPENDENCY_NOT_MERGED,
            "direct dependency has not been integrated into main",
            dependency,
        )
      }
    }
    return null
  }

  private fun validReadyResponse(
      before: PullRequest,
      request: ReadyGateRequest,
      after: PullRequest,
  ): Boolean =
      after.id == before.id &&
          after.subTaskId == before.subTaskId &&
          after.reviewRevision.id == request.reviewRevisionId &&
          after.changeRevision == before.changeRevision &&
          after.state in setOf(PullRequestState.READY, PullRequestState.REVIEW)

  private fun validApprovalResponse(
      before: PullRequest,
      request: ApproveGateRequest,
      diffIdentity: DiffIdentity,
      actor: Actor,
      response: ApproveReviewResponse,
  ): Boolean {
    val after = response.pullRequest
    val approval = response.approval
    return after.id == before.id &&
        after.subTaskId == before.subTaskId &&
        after.reviewRevision.id == before.reviewRevision.id &&
        after.changeRevision.id == request.changeRevisionId &&
        after.changeRevision.diff.identity == diffIdentity &&
        after.ci == CiStatus.PASSED &&
        after.state in
            setOf(
                PullRequestState.READY,
                PullRequestState.REVIEW,
                PullRequestState.APPROVED,
                PullRequestState.QUEUED,
            ) &&
        approval.actor == actor &&
        approval.actor.isHuman &&
        approval.active &&
        approval.changeRevisionId == request.changeRevisionId &&
        approval.diffIdentity == diffIdentity
  }

  private fun canEnterMergeQueue(
      snapshot: WorkflowStoreSnapshot,
      target: ReviewTarget,
      response: ApproveReviewResponse,
  ): Boolean {
    val dependencyIntegrated =
        target.subTask.requires?.let { dependency ->
          snapshot.subTasks
              .firstOrNull { it.id == dependency }
              ?.let { parent ->
                parent.state == SubTaskState.MERGED ||
                    snapshot.integrations.any {
                      it.subTaskId == dependency && it.state == IntegrationState.MERGED
                    }
              }
        } ?: true
    val validations =
        snapshot.checks[target.subTask.id]?.checks.orEmpty().map { check ->
          io.springkit.workflow.domain.Validation(
              id = check.id,
              name = check.name,
              status = check.status,
              revision = check.revision,
              message = check.message,
          )
        }
    return WorkflowRules.canEnterMergeQueue(
        response.pullRequest.copy(state = target.pr.state, approval = response.approval),
        validations,
        dependencyIntegrated,
    )
  }

  private fun validQueueResponse(
      target: ReviewTarget,
      request: ApproveGateRequest,
      entry: MergeQueueEntry,
      snapshot: WorkflowStoreSnapshot,
  ): Boolean =
      entry.subTaskId == target.subTask.id &&
          entry.pullRequestId == target.pr.id &&
          entry.changeRevisionId == request.changeRevisionId &&
          entry.state in
              setOf(
                  MergeQueueState.QUEUED,
                  MergeQueueState.VALIDATING,
                  MergeQueueState.PASSED,
              ) &&
          snapshot.mergeQueue.none { it.id == entry.id && it.subTaskId != target.subTask.id }

  private fun <T> withHuman(
      target: String,
      capability: Capability,
      requestId: String,
      expectedStoreRevision: String?,
      operation: (Actor, GateTransaction) -> PortResult<TransactionMutation<T>>,
  ): WorkflowResult<T> {
    val actorResult = identityPort.currentActor(CurrentActorRequest(requestId))
    val actor =
        when (actorResult) {
          is PortResult.Failure ->
              return actorResult.toWorkflowFailure(FailureCode.EXTERNAL_FAILURE)
          is PortResult.Success -> actorResult.value.actor
        }
    val authorization = identityPort.authorize(AuthorizeRequest(actor, capability, target))
    when (authorization) {
      is PortResult.Failure -> return authorization.toWorkflowFailure(FailureCode.HUMAN_REQUIRED)
      is PortResult.Success -> {
        if (!authorization.value.allowed || !actor.isHuman) {
          return WorkflowResult.Failure(
              FailureData(
                  FailureCode.HUMAN_REQUIRED,
                  authorization.value.reason ?: "a human actor is required",
                  blockedBy =
                      listOf(BlockedBy("HUMAN_REQUIRED", "a human decision is required", target)),
                  next =
                      listOf(
                          NextAction(
                              ActorKind.HUMAN,
                              "perform_human_gate",
                              "workflow gate " + capability.name.lowercase() + " " + target,
                          ),
                      ),
              ),
          )
        }
      }
    }
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = issueId(IdKind.TRANSACTION, requestId, "tx-" + requestId),
            expectedRevision = expectedStoreRevision,
            idempotencyKey = requestId,
        )
    val transaction = WorkflowTransaction(storePort, compensationGateway())
    return transaction.execute(transactionRequest) { snapshot ->
      operation(actor, GateTransaction(snapshot, transactionRequest))
    }
  }

  private fun findTarget(
      snapshot: WorkflowStoreSnapshot,
      subTaskId: SubTaskId,
  ): PortResult<ReviewTarget> {
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == subTaskId }
            ?: return PortResult.Failure(
                PortError(
                    FailureCode.SUBTASK_NOT_FOUND.name,
                    "subtask was not found",
                    target = subTaskId,
                ),
            )
    val pullRequestId =
        subTask.pullRequestId
            ?: return PortResult.Failure(
                PortError(
                    FailureCode.REVIEW_NOT_FOUND.name,
                    "subtask does not have a pull request",
                    target = subTask.id,
                ),
            )
    val pullRequest =
        snapshot.pullRequests.firstOrNull { it.id == pullRequestId }
            ?: return PortResult.Failure(
                PortError(
                    FailureCode.REVIEW_NOT_FOUND.name,
                    "pull request was not found",
                    target = pullRequestId,
                ),
            )
    if (pullRequest.subTaskId != subTask.id) {
      return PortResult.Failure(
          PortError(
              FailureCode.INVARIANT_VIOLATION.name,
              "pull request does not belong to the subtask",
              target = pullRequest.id,
          ),
      )
    }
    return PortResult.Success(ReviewTarget(subTask, pullRequest))
  }

  private fun validateReadyRequest(request: ReadyGateRequest): FailureData? =
      if (
          request.subTaskId.isBlank() ||
              request.reviewRevisionId.isBlank() ||
              request.requestId.isBlank()
      ) {
        failureData(FailureCode.INVALID_ARGUMENT, "ready gate request values must not be blank")
      } else null

  private fun validateApproveRequest(request: ApproveGateRequest): FailureData? =
      if (
          request.subTaskId.isBlank() ||
              request.changeRevisionId.isBlank() ||
              request.diffIdentity?.isBlank() == true ||
              request.requestId.isBlank()
      ) {
        failureData(FailureCode.INVALID_ARGUMENT, "approve gate request values must not be blank")
      } else null

  private fun audit(
      requestId: String,
      gate: GateType,
      actor: Actor,
      targetId: String,
      reviewRevisionId: ReviewRevisionId,
      changeRevisionId: ChangeRevisionId? = null,
      occurredAt: Long,
  ): AuditEntry =
      AuditEntry(
          id =
              issueId(
                  IdKind.AUDIT,
                  requestId + ":" + gate.name.lowercase(),
                  "audit-" + requestId,
              ),
          actor = actor,
          action = gate.name.lowercase(),
          targetId = targetId,
          reviewRevisionId = reviewRevisionId,
          changeRevisionId = changeRevisionId,
          occurredAtEpochMillis = occurredAt,
      )

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null -> 0L
      }

  private fun issueId(kind: IdKind, requestId: String, fallback: String): String =
      when (val result = idPort?.issue(IssueIdRequest(kind, requestId))) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value ?: fallback
        is PortResult.Failure,
        null -> fallback
      }

  private fun compensationGateway(): CompensationPort =
      compensationPort
          ?: object : CompensationPort {
            override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
                PortResult.Success(CompensateResponse(request.change))
          }

  private fun compensatedFailure(
      result: PortResult.Failure,
      changes: List<ChangeReceipt>,
  ): PortResult.Failure {
    val allChanges = (changes + listOfNotNull(result.change)).asReversed()
    val failures = allChanges.mapNotNull { change ->
      if (change.compensation == null) {
        null
      } else {
        when (
            val compensation =
                compensationPort?.compensate(
                    CompensateRequest(change, "review gate operation failed"),
                )
        ) {
          is PortResult.Success -> null
          is PortResult.Failure -> compensation.error
          null ->
              PortError(
                  "COMPENSATION_REQUIRED",
                  "compensation port is not configured",
                  target = change.id,
              )
        }
      }
    }
    if (failures.isEmpty()) return PortResult.Failure(result.error)
    return PortResult.Failure(
        PortError(
            result.error.code,
            result.error.message + "; " + failures.joinToString("; ") { it.message },
            result.error.retryable,
            result.error.target,
        ),
    )
  }

  private fun failure(data: FailureData): PortResult.Failure =
      PortResult.Failure(
          PortError(
              data.code.name,
              data.message,
              target = data.blockedBy.firstOrNull()?.target,
          ),
      )

  private fun failure(
      code: FailureCode,
      message: String,
      target: String? = null,
  ): PortResult.Failure = PortResult.Failure(PortError(code.name, message, target = target))

  private fun failureData(
      code: FailureCode,
      message: String,
      target: String? = null,
  ): FailureData =
      FailureData(
          code = code,
          message = message,
          blockedBy = listOfNotNull(target?.let { BlockedBy(code.name, message, it) }),
      )

  private data class ReviewTarget(val subTask: SubTask, val pr: PullRequest)

  private data class GateTransaction(
      val snapshot: WorkflowStoreSnapshot,
      val request: StoreTransactionRequest,
  )
}

private fun WorkflowStoreSnapshot.withReviewGate(
    subTask: SubTask,
    pullRequest: PullRequest,
    mergeQueue: MergeQueueEntry? = null,
    event: GateRecorded,
    audit: AuditEntry,
): WorkflowStoreSnapshot =
    copy(
        subTasks = subTasks.replaceById(subTask) { it.id },
        pullRequests = pullRequests.replaceById(pullRequest) { it.id },
        mergeQueue =
            if (mergeQueue == null) this.mergeQueue
            else this.mergeQueue.filterNot { it.subTaskId == subTask.id } + mergeQueue,
        eventLog = eventLog.append(event, audit),
    )

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> = map { current ->
  if (id(current) == id(value)) value else current
}
