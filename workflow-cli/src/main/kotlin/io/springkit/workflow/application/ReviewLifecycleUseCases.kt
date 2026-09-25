package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.ChangeRevisionId
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.DomainEvent
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.FeatureFlagId
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewBodyTemplate
import io.springkit.workflow.domain.ReviewChanged
import io.springkit.workflow.domain.ReviewOpened
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewRevisionId
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspaceId
import io.springkit.workflow.domain.toSubTaskState

/** 처음 Draft PR을 게시하는 애플리케이션 요청입니다. */
data class OpenReviewLifecycleRequest(
    val workspaceId: WorkspaceId,
    val subTaskId: SubTaskId,
    val body: String,
    val risk: Risk,
    val exposure: Exposure,
    val featureFlagId: FeatureFlagId? = null,
    val expectedStoreRevision: String? = null,
    val requestId: String = "review-open-$subTaskId",
) {
  init {
    require(workspaceId.isNotBlank()) { "review workspace id must not be blank" }
    require(subTaskId.isNotBlank()) { "review subtask id must not be blank" }
    require(body.isNotBlank()) { "review body must not be blank" }
    require(requestId.isNotBlank()) { "review request id must not be blank" }
    require(exposure != Exposure.FEATURE_FLAG || !featureFlagId.isNullOrBlank()) {
      "feature-flag exposure requires a feature flag id"
    }
    require(exposure != Exposure.UNCHANGED || featureFlagId == null) {
      "unchanged exposure must not have a feature flag id"
    }
  }
}

/** Review 이후 코드 또는 PR 본문을 다시 게시하는 애플리케이션 요청입니다. */
data class UpdateReviewLifecycleRequest(
    val workspaceId: WorkspaceId,
    val subTaskId: SubTaskId,
    val pullRequestId: String,
    val expectedReviewRevisionId: ReviewRevisionId,
    val body: String? = null,
    val expectedStoreRevision: String? = null,
    val requestId: String = "review-update-$pullRequestId-$expectedReviewRevisionId",
) {
  init {
    require(workspaceId.isNotBlank()) { "review workspace id must not be blank" }
    require(subTaskId.isNotBlank()) { "review subtask id must not be blank" }
    require(pullRequestId.isNotBlank()) { "pull request id must not be blank" }
    require(expectedReviewRevisionId.isNotBlank()) {
      "expected review revision id must not be blank"
    }
    require(body == null || body.isNotBlank()) { "review body must not be blank" }
    require(requestId.isNotBlank()) { "review request id must not be blank" }
  }
}

/** Draft PR 게시 결과와 해당 코드 검증 결과입니다. */
data class OpenReviewLifecycleResponse(
    val pullRequest: PullRequest,
    val check: CheckSummary,
    val publishedRevision: String,
)

/** Review 갱신 결과와 코드 변경 여부입니다. */
data class UpdateReviewLifecycleResponse(
    val pullRequest: PullRequest,
    val codeChanged: Boolean,
    val check: CheckSummary,
    val publishedRevision: String?,
)

/** Review 게시, 외부 게이트 시작과 Workflow Store 반영을 하나의 생명주기로 조정합니다. */
class ReviewLifecycleUseCases(
    private val workspacePort: WorkspacePort,
    private val gitPort: GitPort,
    private val gitPublishPort: GitPublishPort,
    private val reviewPort: ReviewPort,
    private val ciPort: CiPort,
    private val aiReviewPort: AiReviewPort? = null,
    private val storePort: WorkflowStorePort,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
    private val featureFlagPort: FeatureFlagPort? = null,
) {
  /** 현재 Worktree를 검증하고 Draft PR과 초기 게이트를 게시합니다. */
  fun open(request: OpenReviewLifecycleRequest): WorkflowResult<OpenReviewLifecycleResponse> {
    ReviewBodyTemplate.validate(request.body)?.let { message ->
      return failure(FailureCode.INVALID_ARGUMENT, message, request.subTaskId)
    }
    val context = loadContext(request.workspaceId, request.subTaskId)
    if (context is ContextFailure) return context.failure
    context as ContextSuccess
    val snapshot = context.snapshot
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "Workflow Store revision이 현재 상태와 다릅니다.",
          snapshot.revision,
      )
    }
    val workspace = context.workspace
    val subTask = context.subTask
    val featureFlagFailure = validateFeatureFlag(request)
    if (featureFlagFailure != null) return featureFlagFailure
    if (snapshot.pullRequests.any { it.subTaskId == subTask.id }) {
      return failure(FailureCode.REVIEW_ALREADY_OPEN, "SubTask의 Review가 이미 열려 있습니다.", subTask.id)
    }
    if (subTask.state != SubTaskState.DEVELOPMENT) {
      return failure(
          FailureCode.INVALID_GATE_STATE,
          "Development 상태의 SubTask만 Review를 열 수 있습니다.",
          subTask.id,
      )
    }
    val status = context.status
    val check = requirePassedCheck(snapshot, subTask.id, status.fingerprint, status.revision)
    if (check is WorkflowResult.Failure) return check
    check as WorkflowResult.Success
    val validationSummary = check.data
    val title = "[${subTask.id}] ${subTask.title}"
    val base =
        if (subTask.requires == null) {
          "main"
        } else {
          snapshot.subTasks.firstOrNull { it.id == subTask.requires }?.branch
              ?: return failure(
                  FailureCode.DEPENDENCY_NOT_FOUND,
                  "직접 필요한 선행 SubTask를 찾을 수 없습니다.",
                  subTask.requires,
              )
        }
    val transaction = begin(snapshot.revision, "review-open:${request.requestId}")
    if (transaction is BeginFailure) return transaction.failure
    transaction as BeginSuccess
    val changes = mutableListOf<ChangeReceipt>()
    val now = now(request.requestId)
    val reviewNumber = snapshot.sequence.review + 1
    val changeNumber = snapshot.sequence.change + 1
    val reviewRevision =
        ReviewRevision(
            id = "rv-$reviewNumber",
            number = reviewNumber,
            body = request.body,
            createdAtEpochMillis = now,
        )
    val provisionalChange =
        ChangeRevision(
            id = "cr-$changeNumber",
            number = changeNumber,
            diff = Diff(identity = status.fingerprint, files = status.conflicts),
            createdAtEpochMillis = now,
        )
    val published =
        remember(
            gitPublishPort.publish(
                PublishBranchRequest(
                    workspaceId = workspace.id,
                    branch = subTask.branch,
                    commitTitle = title,
                    expectedRevision = status.revision,
                    expectedFingerprint = status.fingerprint,
                )
            ),
            changes,
        ) ?: return recover(transaction.request, changes, lastFailure)
    val changeRevision =
        provisionalChange.copy(diff = provisionalChange.diff.copy(identity = published.fingerprint))
    val opened =
        remember(
            reviewPort.open(
                OpenReviewRequest(
                    subTaskId = subTask.id,
                    title = title,
                    body = request.body,
                    base = base,
                    branch = subTask.branch,
                    risk = request.risk,
                    exposure = request.exposure,
                    featureFlagId = request.featureFlagId,
                    changeRevision = changeRevision,
                    reviewRevision = reviewRevision,
                )
            ),
            changes,
        ) ?: return recover(transaction.request, changes, lastFailure)
    if (
        !validOpen(
            opened.pullRequest,
            subTask,
            title,
            base,
            request,
            reviewRevision,
            changeRevision,
        )
    ) {
      return recover(
          transaction.request,
          changes,
          failureData(
              FailureCode.INVARIANT_VIOLATION,
              "Review provider가 초기 PR metadata를 다르게 반환했습니다.",
          ),
      )
    }
    val ci =
        remember(
            ciPort.start(
                StartCiRequest(
                    pullRequestId = opened.pullRequest.id,
                    revision = published.revision,
                    checks = validationSummary.checks.map { it.toValidation(published.revision) },
                )
            ),
            changes,
        ) ?: return recover(transaction.request, changes, lastFailure)
    val aiStatus =
        aiReviewPort?.let { port ->
          remember(
                  port.start(
                      StartAiReviewRequest(
                          pullRequestId = opened.pullRequest.id,
                          reviewRevisionId = reviewRevision.id,
                          changeRevisionId = changeRevision.id,
                          diff = changeRevision.diff,
                      )
                  ),
                  changes,
              )
              ?.status ?: return recover(transaction.request, changes, lastFailure)
        } ?: AiReviewStatus.PENDING
    val storedCheck = validationSummary.retarget(published.fingerprint, published.revision)
    val pullRequest =
        opened.pullRequest.copy(
            title = title,
            body = request.body,
            base = base,
            state = PullRequestState.DRAFT,
            reviewRevision = reviewRevision,
            changeRevision = opened.pullRequest.changeRevision,
            ci = ci.run.status,
            aiReview = aiStatus,
        )
    val storedSnapshot =
        snapshot.copy(
            sequence = snapshot.sequence.copy(review = reviewNumber, change = changeNumber),
            checks = snapshot.checks + (subTask.id to storedCheck),
            pullRequests = snapshot.pullRequests.replaceReview(pullRequest),
            subTasks =
                snapshot.subTasks.replaceSubTask(
                    subTask.copy(
                        pullRequestId = pullRequest.id,
                        state = pullRequest.state.toSubTaskState(),
                        risk = request.risk,
                        exposure = request.exposure,
                        featureFlagId = request.featureFlagId,
                    )
                ),
        )
    val event =
        ReviewOpened(
            targetId = pullRequest.id,
            subTaskId = subTask.id,
            reviewRevisionId = reviewRevision.id,
            changeRevisionId = changeRevision.id,
            occurredAtEpochMillis = now,
        )
    val saved =
        persist(
            transaction.request,
            storedSnapshot,
            event,
            audit(event, reviewRevision.id, changeRevision.id, now),
            changes,
        )
    return when (saved) {
      is WorkflowResult.Failure -> saved
      is WorkflowResult.Success ->
          WorkflowResult.Success(
              OpenReviewLifecycleResponse(pullRequest, storedCheck, published.revision),
              next = listOf(NextAction(ActorKind.HUMAN, "review")),
          )
    }
  }

  /** 현재 Worktree의 코드 또는 PR 본문을 같은 Review에 다시 게시합니다. */
  fun update(request: UpdateReviewLifecycleRequest): WorkflowResult<UpdateReviewLifecycleResponse> {
    request.body?.let { body ->
      ReviewBodyTemplate.validate(body)?.let { message ->
        return failure(FailureCode.INVALID_ARGUMENT, message, request.pullRequestId)
      }
    }
    val context = loadContext(request.workspaceId, request.subTaskId)
    if (context is ContextFailure) return context.failure
    context as ContextSuccess
    val snapshot = context.snapshot
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "Workflow Store revision이 현재 상태와 다릅니다.",
          snapshot.revision,
      )
    }
    val workspace = context.workspace
    val subTask = context.subTask
    val stored =
        snapshot.pullRequests.firstOrNull { it.id == request.pullRequestId }
            ?: return failure(
                FailureCode.REVIEW_NOT_FOUND,
                "SubTask의 PR을 찾을 수 없습니다.",
                request.pullRequestId,
            )
    if (stored.subTaskId != subTask.id) {
      return failure(
          FailureCode.INVALID_TARGET_SELECTION,
          "PR이 현재 SubTask에 속하지 않습니다.",
          request.pullRequestId,
      )
    }
    val remote =
        when (val result = reviewPort.get(GetReviewRequest(stored.id))) {
          is PortResult.Failure -> return portFailure(result)
          is PortResult.Success -> result.value.pullRequest
        }
    if (
        remote.reviewRevision.id != request.expectedReviewRevisionId ||
            stored.reviewRevision.id != request.expectedReviewRevisionId
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "Review revision이 현재 상태와 다릅니다.",
          request.expectedReviewRevisionId,
      )
    }
    val status = context.status
    val check = requirePassedCheck(snapshot, subTask.id, status.fingerprint, status.revision)
    if (check is WorkflowResult.Failure) return check
    val codeChanged = stored.changeRevision.diff.identity != status.fingerprint
    if (!codeChanged && request.body == null) {
      return failure(FailureCode.INVALID_ARGUMENT, "Review update에는 본문 또는 코드 변경이 필요합니다.")
    }
    val transaction = begin(snapshot.revision, "review-update:${request.requestId}")
    if (transaction is BeginFailure) return transaction.failure
    transaction as BeginSuccess
    val changes = mutableListOf<ChangeReceipt>()
    val now = now(request.requestId)
    val reviewNumber = snapshot.sequence.review + 1
    val changeNumber = snapshot.sequence.change + if (codeChanged) 1 else 0
    val reviewRevision =
        ReviewRevision(
            id = "rv-$reviewNumber",
            number = reviewNumber,
            body = request.body ?: stored.body,
            threads = stored.reviewRevision.threads,
            createdAtEpochMillis = now,
        )
    val published =
        if (codeChanged) {
          remember(
              gitPublishPort.publish(
                  PublishBranchRequest(
                      workspaceId = workspace.id,
                      branch = subTask.branch,
                      commitTitle = stored.title,
                      expectedRevision = status.revision,
                      expectedFingerprint = status.fingerprint,
                  )
              ),
              changes,
          ) ?: return recover(transaction.request, changes, lastFailure)
        } else {
          null
        }
    val changeRevision =
        if (codeChanged) {
          val publishedResult =
              published
                  ?: return recover(
                      transaction.request,
                      changes,
                      failureData(FailureCode.INVARIANT_VIOLATION, "게시 결과가 없습니다."),
                  )
          ChangeRevision(
              id = "cr-$changeNumber",
              number = changeNumber,
              diff = stored.changeRevision.diff.copy(identity = publishedResult.fingerprint),
              createdAtEpochMillis = now,
          )
        } else {
          stored.changeRevision
        }
    val updated =
        remember(
            reviewPort.update(
                UpdateReviewRequest(
                    pullRequestId = stored.id,
                    expectedReviewRevisionId = request.expectedReviewRevisionId,
                    body = request.body,
                    changeRevision = changeRevision.takeIf { codeChanged },
                    reviewRevision = reviewRevision,
                )
            ),
            changes,
        ) ?: return recover(transaction.request, changes, lastFailure)
    if (
        updated.pullRequest.id != stored.id ||
            updated.pullRequest.reviewRevision.id != reviewRevision.id
    ) {
      return recover(
          transaction.request,
          changes,
          failureData(
              FailureCode.INVARIANT_VIOLATION,
              "Review provider가 갱신된 revision을 반환하지 않았습니다.",
          ),
      )
    }
    var ciStatus = stored.ci
    var aiStatus = stored.aiReview
    if (codeChanged) {
      val check =
          snapshot.checks[subTask.id]
              ?: return recover(
                  transaction.request,
                  changes,
                  failureData(FailureCode.CHECK_NOT_PASSED, "현재 코드의 검증 결과가 없습니다."),
              )
      val publishedResult =
          published
              ?: return recover(
                  transaction.request,
                  changes,
                  failureData(FailureCode.INVARIANT_VIOLATION, "게시 결과가 없습니다."),
              )
      val ci =
          remember(
              ciPort.start(
                  StartCiRequest(
                      pullRequestId = stored.id,
                      revision = publishedResult.revision,
                      checks = check.checks.map { it.toValidation(publishedResult.revision) },
                  )
              ),
              changes,
          ) ?: return recover(transaction.request, changes, lastFailure)
      ciStatus = ci.run.status
      aiStatus =
          aiReviewPort?.let { port ->
            remember(
                    port.start(
                        StartAiReviewRequest(
                            stored.id,
                            reviewRevision.id,
                            changeRevision.id,
                            changeRevision.diff,
                        )
                    ),
                    changes,
                )
                ?.status ?: return recover(transaction.request, changes, lastFailure)
          } ?: AiReviewStatus.PENDING
    }
    val storedCheck =
        if (codeChanged) {
          val publishedResult =
              published
                  ?: return recover(
                      transaction.request,
                      changes,
                      failureData(FailureCode.INVARIANT_VIOLATION, "게시 결과가 없습니다."),
                  )
          snapshot.checks
              .getValue(subTask.id)
              .retarget(publishedResult.fingerprint, publishedResult.revision)
        } else {
          snapshot.checks.getValue(subTask.id)
        }
    val pullRequest =
        updated.pullRequest.copy(
            body = request.body ?: stored.body,
            reviewRevision = reviewRevision,
            changeRevision = updated.pullRequest.changeRevision,
            approval = if (codeChanged) null else stored.approval,
            ci = ciStatus,
            aiReview = aiStatus,
        )
    val storedSnapshot =
        snapshot.copy(
            sequence = snapshot.sequence.copy(review = reviewNumber, change = changeNumber),
            checks = snapshot.checks + (subTask.id to storedCheck),
            pullRequests = snapshot.pullRequests.replaceReview(pullRequest),
            subTasks =
                snapshot.subTasks.replaceSubTask(
                    subTask.copy(state = pullRequest.state.toSubTaskState())
                ),
        )
    val event =
        ReviewChanged(
            targetId = pullRequest.id,
            reviewRevisionId = reviewRevision.id,
            changeRevisionId = changeRevision.id.takeIf { codeChanged },
            diffChanged = codeChanged,
            occurredAtEpochMillis = now,
        )
    val saved =
        persist(
            transaction.request,
            storedSnapshot,
            event,
            audit(event, reviewRevision.id, changeRevision.id.takeIf { codeChanged }, now),
            changes,
        )
    return when (saved) {
      is WorkflowResult.Failure -> saved
      is WorkflowResult.Success ->
          WorkflowResult.Success(
              UpdateReviewLifecycleResponse(
                  pullRequest,
                  codeChanged,
                  storedCheck,
                  published?.revision,
              ),
              next = listOf(NextAction(ActorKind.HUMAN, "review")),
          )
    }
  }

  private fun loadContext(workspaceId: WorkspaceId, subTaskId: SubTaskId): ContextResult {
    val workspace =
        when (
            val result =
                workspacePort.get(
                    WorkspaceLookupRequest(workspaceId = workspaceId, subTaskId = subTaskId)
                )
        ) {
          is PortResult.Failure -> return ContextFailure(portFailure(result))
          is PortResult.Success -> result.value.workspace
        }
    if (!workspace.managed) {
      return ContextFailure(
          failure(FailureCode.WORKTREE_REQUIRED, "관리 Worktree만 Review를 게시할 수 있습니다.", workspace.id)
      )
    }
    if (workspace.subTaskId != subTaskId) {
      return ContextFailure(
          failure(
              FailureCode.INVALID_TARGET_SELECTION,
              "Workspace가 현재 SubTask에 연결되지 않았습니다.",
              workspace.id,
          )
      )
    }
    val snapshot =
        when (
            val result =
                storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL, subTaskId = subTaskId))
        ) {
          is PortResult.Failure -> return ContextFailure(portFailure(result))
          is PortResult.Success -> result.value.snapshot
        }
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == subTaskId }
            ?: return ContextFailure(
                failure(FailureCode.SUBTASK_NOT_FOUND, "SubTask를 찾을 수 없습니다.", subTaskId)
            )
    val status =
        when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
          is PortResult.Failure -> return ContextFailure(portFailure(result))
          is PortResult.Success -> result.value.status
        }
    return ContextSuccess(snapshot, workspace, subTask, status)
  }

  private fun validateFeatureFlag(request: OpenReviewLifecycleRequest): WorkflowResult.Failure? {
    if (request.exposure != Exposure.FEATURE_FLAG) return null
    val featureFlagId =
        request.featureFlagId
            ?: return failure(FailureCode.INVALID_ARGUMENT, "Feature Flag 식별자가 필요합니다.")
    val port =
        featureFlagPort
            ?: return failure(
                FailureCode.INVALID_GATE_STATE,
                "Feature Flag의 안전한 기본 동작을 확인할 공급자가 없습니다.",
                featureFlagId,
            )
    return when (val result = port.validateDefault(ValidateFeatureFlagRequest(featureFlagId))) {
      is PortResult.Failure -> portFailure(result)
      is PortResult.Success ->
          if (result.value.safeDefault) {
            null
          } else {
            failure(
                FailureCode.INVALID_GATE_STATE,
                "Feature Flag의 기본 동작이 안전하지 않습니다.",
                featureFlagId,
            )
          }
    }
  }

  private fun requirePassedCheck(
      snapshot: WorkflowStoreSnapshot,
      subTaskId: SubTaskId,
      fingerprint: String,
      revision: String,
  ): WorkflowResult<CheckSummary> {
    val check =
        snapshot.checks[subTaskId]
            ?: return failure(
                FailureCode.CHECK_NOT_PASSED,
                "현재 fingerprint의 check 결과가 없습니다.",
                subTaskId,
            )
    if (!check.appliesTo(fingerprint, revision)) {
      return failure(
          FailureCode.STALE_DIFF_IDENTITY,
          "현재 fingerprint에 적용되는 check 결과가 없습니다.",
          subTaskId,
      )
    }
    if (!check.passed) {
      return failure(FailureCode.CHECK_NOT_PASSED, "필수 check가 모두 PASSED가 아닙니다.", subTaskId)
    }
    return WorkflowResult.Success(check)
  }

  private fun begin(expectedRevision: String, key: String): BeginResult {
    val request = StoreTransactionRequest("tx-$key", expectedRevision, key)
    return when (val result = storePort.begin(request)) {
      is PortResult.Failure -> BeginFailure(portFailure(result))
      is PortResult.Success ->
          if (result.value.state == StoreTransactionState.OPEN) BeginSuccess(request)
          else
              BeginFailure(
                  failure(FailureCode.STORE_FAILURE, "Workflow Store transaction이 열리지 않았습니다.")
              )
    }
  }

  private fun <T : ChangeResponse> remember(
      result: PortResult<T>,
      changes: MutableList<ChangeReceipt>,
  ): T? {
    return when (result) {
      is PortResult.Success -> {
        changes += result.value.change
        lastFailure = failure(FailureCode.EXTERNAL_FAILURE, "외부 동작이 실패했습니다.")
        result.value
      }
      is PortResult.Failure -> {
        result.change?.let(changes::add)
        lastFailure = portFailure(result)
        null
      }
    }
  }

  private var lastFailure: WorkflowResult.Failure =
      failure(FailureCode.EXTERNAL_FAILURE, "외부 동작이 실패했습니다.")

  private fun recover(
      transaction: StoreTransactionRequest,
      changes: List<ChangeReceipt>,
      failure: WorkflowResult.Failure,
  ): WorkflowResult.Failure {
    storePort.rollback(transaction)
    val compensationFailures =
        changes.asReversed().mapNotNull { change ->
          val compensation =
              compensationPort
                  ?: return@mapNotNull if (change.compensation == null) {
                    null
                  } else {
                    PortError(
                        "COMPENSATION_PORT_UNAVAILABLE",
                        "외부 변경을 보상할 CompensationPort가 구성되지 않았습니다.",
                    )
                  }
          when (
              val result = compensation.compensate(CompensateRequest(change, failure.data.message))
          ) {
            is PortResult.Failure -> result.error
            is PortResult.Success -> null
          }
        }
    return if (compensationFailures.isEmpty()) failure
    else
        failure.copy(
            data =
                failure.data.copy(
                    blockedBy =
                        failure.data.blockedBy +
                            BlockedBy(
                                "COMPENSATION_REQUIRED",
                                compensationFailures.joinToString("; ") { it.message },
                            ),
                    next = failure.data.next + NextAction(ActorKind.WORKFLOW, "reconcile_review"),
                )
        )
  }

  private fun persist(
      transaction: StoreTransactionRequest,
      snapshot: WorkflowStoreSnapshot,
      event: DomainEvent,
      audit: AuditEntry,
      changes: List<ChangeReceipt>,
  ): WorkflowResult<Unit> {
    val expectedRevision = requireNotNull(transaction.expectedRevision)
    when (
        val result =
            storePort.write(
                StoreWriteRequest(transaction.transactionId, expectedRevision, snapshot)
            )
    ) {
      is PortResult.Failure -> return recover(transaction, changes, portFailure(result))
      is PortResult.Success -> Unit
    }
    when (
        val result = storePort.append(StoreEventRequest(transaction.transactionId, event, audit))
    ) {
      is PortResult.Failure -> return recover(transaction, changes, portFailure(result))
      is PortResult.Success -> Unit
    }
    return when (val result = storePort.commit(transaction)) {
      is PortResult.Failure -> recover(transaction, changes, portFailure(result))
      is PortResult.Success -> WorkflowResult.Success(Unit)
    }
  }

  private fun validOpen(
      pullRequest: PullRequest,
      subTask: SubTask,
      title: String,
      base: String,
      request: OpenReviewLifecycleRequest,
      reviewRevision: ReviewRevision,
      changeRevision: ChangeRevision,
  ): Boolean =
      pullRequest.subTaskId == subTask.id &&
          pullRequest.title == title &&
          pullRequest.body == request.body &&
          pullRequest.base == base &&
          pullRequest.state == PullRequestState.DRAFT &&
          pullRequest.risk == request.risk &&
          pullRequest.exposure == request.exposure &&
          pullRequest.featureFlagId == request.featureFlagId &&
          pullRequest.reviewRevision.id == reviewRevision.id &&
          pullRequest.changeRevision.id == changeRevision.id

  private fun audit(
      event: DomainEvent,
      reviewRevisionId: ReviewRevisionId,
      changeRevisionId: ChangeRevisionId?,
      occurredAt: Long,
  ): AuditEntry =
      AuditEntry(
          id = "audit-${event.targetId}-${occurredAt}-${reviewRevisionId}",
          actor = Actor("workflow", ActorKind.WORKFLOW),
          action = event::class.simpleName ?: "review-change",
          targetId = event.targetId,
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

  private fun portFailure(result: PortResult.Failure): WorkflowResult.Failure =
      failure(
          FailureCode.entries.firstOrNull { it.name == result.error.code.uppercase() }
              ?: FailureCode.EXTERNAL_FAILURE,
          result.error.message,
          result.error.target,
      )

  private fun failure(
      code: FailureCode,
      message: String,
      target: String? = null,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code = code,
              message = message,
              blockedBy = target?.let { listOf(BlockedBy(code.name, message, it)) }.orEmpty(),
          )
      )

  private fun failureData(code: FailureCode, message: String): WorkflowResult.Failure =
      failure(code, message)

  private sealed interface ContextResult

  private data class ContextSuccess(
      val snapshot: WorkflowStoreSnapshot,
      val workspace: Workspace,
      val subTask: SubTask,
      val status: GitStatus,
  ) : ContextResult

  private data class ContextFailure(val failure: WorkflowResult.Failure) : ContextResult

  private sealed interface BeginResult

  private data class BeginSuccess(val request: StoreTransactionRequest) : BeginResult

  private data class BeginFailure(val failure: WorkflowResult.Failure) : BeginResult
}

private fun CheckResult.toValidation(revisionOverride: String? = null): Validation =
    Validation(
        id = id,
        name = name,
        status = status,
        required = true,
        revision = revisionOverride ?: revision,
        message = message,
    )

private fun CheckSummary.retarget(fingerprint: String, revision: String): CheckSummary =
    copy(
        fingerprint = fingerprint,
        revision = revision,
        checks = checks.map { it.copy(fingerprint = fingerprint, revision = revision) },
    )

private fun List<PullRequest>.replaceReview(value: PullRequest): List<PullRequest> =
    filterNot { it.id == value.id } + value

private fun List<SubTask>.replaceSubTask(value: SubTask): List<SubTask> =
    filterNot { it.id == value.id } + value
