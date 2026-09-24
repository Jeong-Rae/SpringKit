package io.springkit.workflow.application

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.ChangeRevisionId
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.DiffIdentity
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MainRevision
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseId
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskCleanupState
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskId
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace

/** 상태 조회 대상의 타입입니다. 요청마다 대상 하나만 평가합니다. */
sealed interface StatusSelector {
  data object CurrentWorkspace : StatusSelector

  data class SubTask(val id: SubTaskId) : StatusSelector

  data class Task(val id: TaskId) : StatusSelector

  data class Candidate(val id: CandidateId) : StatusSelector

  data class Release(val id: ReleaseId) : StatusSelector
}

/** 애플리케이션 경계와 CLI 어댑터가 받는 입력입니다. */
data class StatusRequest(
    val selector: StatusSelector? = null,
    val subTaskId: SubTaskId? = null,
    val taskId: TaskId? = null,
    val candidateId: CandidateId? = null,
    val releaseId: ReleaseId? = null,
) {
  fun selected(): StatusSelection =
      if (selector != null) {
        StatusSelection.Typed(selector)
      } else {
        StatusSelection.Values(subTaskId, taskId, candidateId, releaseId)
      }
}

/** CLI 방식의 선택적 값을 검증한 뒤 만든 모호하지 않은 선택자입니다. */
sealed interface StatusSelection {
  data class Typed(val selector: StatusSelector) : StatusSelection

  data class Values(
      val subTaskId: SubTaskId?,
      val taskId: TaskId?,
      val candidateId: CandidateId?,
      val releaseId: ReleaseId?,
  ) : StatusSelection
}

data class StatusWorkspace(
    val workspace: Workspace,
    val content: ContentSnapshot? = null,
    val git: GitStatus? = null,
) {
  val path: String
    get() = workspace.path.value
}

data class StatusReview(
    val pullRequest: PullRequest,
    val state: PullRequestState = pullRequest.state,
    val reviewRevision: String = pullRequest.reviewRevision.id,
    val changeRevision: ChangeRevisionId = pullRequest.changeRevision.id,
    val diffIdentity: DiffIdentity = pullRequest.changeRevision.diff.identity,
    val risk: Risk = pullRequest.risk,
    val exposure: Exposure = pullRequest.exposure,
    val featureFlagId: String? = pullRequest.featureFlagId,
    val threads: List<ReviewThread> = pullRequest.reviewRevision.threads,
    val ready: Boolean = state != PullRequestState.DRAFT,
    val approval: io.springkit.workflow.domain.Approval? = pullRequest.approval,
    val ci: CiStatus = pullRequest.ci,
    val aiReview: AiReviewStatus = pullRequest.aiReview,
) {
  val reviewRevisionObject: ReviewRevision
    get() = pullRequest.reviewRevision
}

data class StatusIntegration(
    val integration: Integration,
    val mergeQueue: MergeQueueEntry? = integration.mergeQueue,
    val mainRevision: MainRevision? = integration.mainRevision,
    val state: IntegrationState = integration.state,
)

data class StatusDeployment(
    val candidate: DeploymentCandidate,
    val candidateId: CandidateId = candidate.id,
    val state: DeploymentCandidateState = candidate.state,
    val mainRevision: MainRevision = candidate.mainRevision,
    val includedSubTasks: List<SubTaskId> = candidate.includedSubTasks,
    val risk: Risk = candidate.risk,
    val gateRequired: Boolean = candidate.deployGateRequired,
)

data class StatusRelease(
    val release: Release,
    val releaseId: ReleaseId = release.id,
    val featureFlagId: String = release.featureFlagId,
    val state: ReleaseState = release.state,
    val gateRequired: Boolean = release.state == ReleaseState.AWAITING_RELEASE_APPROVAL,
)

/** Workflow와 소유 시스템 포트로 구성한 공급자 독립적인 상태입니다. */
data class StatusResponse(
    val task: Task? = null,
    val subTask: SubTask? = null,
    val subTasks: List<StatusResponse> = emptyList(),
    val workspace: StatusWorkspace? = null,
    val review: StatusReview? = null,
    val integration: StatusIntegration? = null,
    val deployment: StatusDeployment? = null,
    val release: StatusRelease? = null,
    val blockedBy: List<BlockedBy> = emptyList(),
    val next: List<NextAction> = emptyList(),
) {
  val subtask: SubTask?
    get() = subTask
}

/** 공급자나 Workflow Store를 변경하지 않고 일관된 상태를 조회합니다. */
class StatusUseCase(
    private val storePort: WorkflowStorePort,
    private val taskPort: TaskPort? = null,
    private val workspacePort: WorkspacePort? = null,
    private val contentPort: ContentPort? = null,
    private val gitPort: GitPort? = null,
    private val reviewPort: ReviewPort? = null,
    private val validationPort: ValidationPort? = null,
    private val ciPort: CiPort? = null,
    private val aiReviewPort: AiReviewPort? = null,
    private val mergeQueuePort: MergeQueuePort? = null,
    private val deploymentPort: DeploymentPort? = null,
    private val releasePort: ReleasePort? = null,
) {
  private companion object {
    const val DEPENDENCY_NOT_MERGED = "DEPENDENCY_NOT_MERGED"
    const val VALIDATION_REQUIRED = "VALIDATION_REQUIRED"
    const val CI_NOT_PASSED = "CI_NOT_PASSED"
    const val AI_REVIEW_FAILED = "AI_REVIEW_FAILED"
    const val REQUIRED_THREAD_OPEN = "REQUIRED_THREAD_OPEN"
    const val MERGE_QUEUE_FAILED = "MERGE_QUEUE_FAILED"
    const val DEPLOYMENT_GATE_BLOCKED = "DEPLOYMENT_GATE_BLOCKED"

    const val RESOLVE_REQUIRED_THREADS = "resolve_required_threads"
    const val FIX_VALIDATION = "fix_validation"
    const val AWAIT_CI = "await_ci"
    const val AWAIT_DEPENDENCY = "await_dependency"
    const val FIX_MERGE_QUEUE = "fix_merge_queue"
  }

  fun status(request: StatusRequest = StatusRequest()): WorkflowResult<StatusResponse> =
      execute(request)

  fun handle(request: StatusRequest = StatusRequest()): WorkflowResult<StatusResponse> =
      execute(request)

  fun execute(request: StatusRequest = StatusRequest()): WorkflowResult<StatusResponse> {
    val selection =
        when (val result = resolveSelection(request.selected())) {
          is WorkflowResult.Success -> result.data
          is WorkflowResult.Failure -> return result
        }

    val snapshot =
        when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
          is PortResult.Success -> result.value.snapshot
          is PortResult.Failure -> return failure(result.error, FailureCode.STORE_FAILURE)
        }

    return when (selection) {
      StatusSelector.CurrentWorkspace -> currentWorkspace(snapshot)
      is StatusSelector.SubTask -> subTask(snapshot, selection.id, current = false)
      is StatusSelector.Task -> task(snapshot, selection.id)
      is StatusSelector.Candidate -> candidate(snapshot, selection.id)
      is StatusSelector.Release -> release(snapshot, selection.id)
    }
  }

  private fun resolveSelection(selection: StatusSelection): WorkflowResult<StatusSelector> =
      when (selection) {
        is StatusSelection.Typed -> WorkflowResult.Success(selection.selector)
        is StatusSelection.Values -> {
          val values =
              listOfNotNull(
                  selection.subTaskId?.let { StatusSelector.SubTask(it) },
                  selection.taskId?.let { StatusSelector.Task(it) },
                  selection.candidateId?.let { StatusSelector.Candidate(it) },
                  selection.releaseId?.let { StatusSelector.Release(it) },
              )
          when {
            values.size > 1 ->
                invalid(
                    "status target selection accepts at most one of subtask, task, candidate and release",
                )
            values.singleOrNull() != null -> WorkflowResult.Success(values.single())
            else -> WorkflowResult.Success(StatusSelector.CurrentWorkspace)
          }
        }
      }

  private fun currentWorkspace(snapshot: WorkflowStoreSnapshot): WorkflowResult<StatusResponse> {
    val workspaceResult =
        workspacePort?.get(WorkspaceLookupRequest())
            ?: return failureCode(
                FailureCode.WORKSPACE_NOT_FOUND,
                "current workspace lookup is not configured",
            )
    val workspace =
        when (workspaceResult) {
          is PortResult.Success -> workspaceResult.value
          is PortResult.Failure ->
              return failure(workspaceResult.error, FailureCode.WORKSPACE_NOT_FOUND)
        }
    val subTaskId = workspace.workspace.subTaskId
    val storedSubTask = snapshot.subTasks.firstOrNull { it.id == subTaskId }
    if (storedSubTask?.cleanupState?.let { it >= SubTaskCleanupState.WORKTREE_REMOVED } == true) {
      return subTask(snapshot, subTaskId, current = false)
    }
    return subTask(snapshot, subTaskId, current = true, workspaceResult = workspace)
  }

  private fun task(snapshot: WorkflowStoreSnapshot, id: TaskId): WorkflowResult<StatusResponse> {
    val stored = snapshot.tasks.firstOrNull { it.id == id || it.externalId.value == id }
    val task =
        if (taskPort == null) {
          stored
        } else {
          when (val result = taskPort.get(TaskLookupRequest(taskId = stored?.id ?: id))) {
            is PortResult.Success -> result.value.task
            is PortResult.Failure -> return failure(result.error, FailureCode.TASK_NOT_FOUND, id)
          }
        }
    if (task == null) return failureCode(FailureCode.TASK_NOT_FOUND, "task was not found", id)

    val subTaskIds =
        if (task.subTaskIds.isNotEmpty()) task.subTaskIds
        else {
          snapshot.subTasks.filter { it.taskId == task.id }.map { it.id }
        }
    val statuses = mutableListOf<StatusResponse>()
    for (subTaskId in subTaskIds) {
      when (val result = subTask(snapshot, subTaskId, current = false)) {
        is WorkflowResult.Success -> statuses += result.data
        is WorkflowResult.Failure -> return result
      }
    }
    return WorkflowResult.Success(StatusResponse(task = task, subTasks = statuses))
  }

  private fun subTask(
      snapshot: WorkflowStoreSnapshot,
      id: SubTaskId,
      current: Boolean,
      workspaceResult: WorkspaceLookupResponse? = null,
  ): WorkflowResult<StatusResponse> {
    val stored = snapshot.subTasks.firstOrNull { it.id == id }
    val subTask =
        if (taskPort == null) {
          stored
        } else {
          when (val result = taskPort.getSubTask(SubTaskLookupRequest(id))) {
            is PortResult.Success -> result.value.subTask
            is PortResult.Failure ->
                if (stored == null) return failure(result.error, FailureCode.SUBTASK_NOT_FOUND, id)
                else stored
          }
        }
    if (subTask == null) {
      return failureCode(FailureCode.SUBTASK_NOT_FOUND, "subtask was not found", id)
    }

    val workspace =
        if (workspaceResult != null) {
          readWorkspace(workspaceResult)
        } else {
          val storedWorkspace =
              subTask.workspace ?: snapshot.workspaces.firstOrNull { it.subTaskId == id }
          val snapshotWorkspace = storedWorkspace?.takeIf {
            (stored?.cleanupState ?: subTask.cleanupState) < SubTaskCleanupState.WORKTREE_REMOVED
          }
          val workspaceLookup =
              if (storedWorkspace != null && snapshotWorkspace == null) null
              else
                  workspacePort?.get(
                      WorkspaceLookupRequest(
                          workspaceId = snapshotWorkspace?.id,
                          subTaskId = id,
                      )
                  )
          when (workspaceLookup) {
            is PortResult.Success -> readWorkspace(workspaceLookup.value)
            is PortResult.Failure ->
                if (current)
                    return failure(workspaceLookup.error, FailureCode.WORKSPACE_NOT_FOUND, id)
                else snapshotWorkspace?.let { readWorkspace(WorkspaceLookupResponse(it)) }
            null -> snapshotWorkspace?.let { readWorkspace(WorkspaceLookupResponse(it)) }
          }
        }
    val reviewSnapshot = snapshot.pullRequests.firstOrNull { it.subTaskId == id }
    val review =
        if (reviewSnapshot == null || reviewPort == null) {
          reviewSnapshot
        } else {
          when (val result = reviewPort.get(GetReviewRequest(reviewSnapshot.id))) {
            is PortResult.Success -> result.value.pullRequest
            is PortResult.Failure ->
                return failure(result.error, FailureCode.REVIEW_NOT_FOUND, reviewSnapshot.id)
          }
        }
    val validations =
        when (val result = readValidation(workspace)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val ciStatus =
        when (val result = readCi(review)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val aiReviewStatus =
        when (val result = readAiReview(review)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val queueEntries =
        when (val result = readQueue(id, review)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val integration =
        snapshot.integrations.firstOrNull { it.subTaskId == id }
            ?: Integration(id, mergeQueue = queueEntries.firstOrNull())
    val effectiveReview =
        review?.copy(
            ci = ciStatus,
            aiReview = aiReviewStatus,
        )
    val candidate = candidateFor(snapshot, subTask)
    val effectiveCandidate =
        when (val result = readCandidate(candidate)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val effectiveRelease =
        when (val result = readRelease(effectiveCandidate ?: candidate)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }

    val blockers =
        blockers(
            snapshot,
            subTask,
            effectiveReview,
            validations,
            integration,
            effectiveCandidate,
        )
    val next =
        nextActions(
            snapshot,
            subTask,
            effectiveReview,
            blockers,
            integration,
            effectiveCandidate,
            effectiveRelease,
        )
    return WorkflowResult.Success(
        StatusResponse(
            task = snapshot.tasks.firstOrNull { it.id == subTask.taskId },
            subTask = subTask,
            workspace = workspace,
            review = effectiveReview?.let(::StatusReview),
            integration = StatusIntegration(integration),
            deployment = effectiveCandidate?.let(::StatusDeployment),
            release = effectiveRelease?.let(::StatusRelease),
            blockedBy = blockers,
            next = next,
        ),
    )
  }

  private fun candidate(
      snapshot: WorkflowStoreSnapshot,
      id: CandidateId,
  ): WorkflowResult<StatusResponse> {
    val stored = snapshot.candidates.firstOrNull { it.id == id }
    val candidate =
        when (val result = readCandidate(stored)) {
          is ReadResult.Value ->
              result.value
                  ?: return failureCode(
                      FailureCode.DEPLOYMENT_NOT_FOUND,
                      "deployment candidate was not found",
                      id,
                  )
          is ReadResult.Failure -> return result.result
        }
    val statusRelease =
        when (val result = readRelease(candidate)) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val next = candidateNext(candidate) + (statusRelease?.let(::releaseNext).orEmpty())
    return WorkflowResult.Success(
        StatusResponse(
            deployment = StatusDeployment(candidate),
            release = statusRelease?.let(::StatusRelease),
            next = next,
        ),
    )
  }

  private fun release(
      snapshot: WorkflowStoreSnapshot,
      id: ReleaseId,
  ): WorkflowResult<StatusResponse> {
    val stored = snapshot.releases.firstOrNull { it.id == id }
    val result =
        if (releasePort == null) {
          stored?.let { ReadResult.Value(it) }
              ?: ReadResult.Failure(
                  failureCode(FailureCode.RELEASE_NOT_FOUND, "release was not found", id)
              )
        } else {
          when (val response = releasePort.get(GetReleaseRequest(releaseId = id))) {
            is PortResult.Success ->
                response.value.releases.firstOrNull()?.let { ReadResult.Value(it) }
                    ?: ReadResult.Failure(
                        failureCode(FailureCode.RELEASE_NOT_FOUND, "release was not found", id)
                    )
            is PortResult.Failure ->
                ReadResult.Failure(failure(response.error, FailureCode.RELEASE_NOT_FOUND, id))
          }
        }
    val value =
        when (result) {
          is ReadResult.Value -> result.value
          is ReadResult.Failure -> return result.result
        }
    val candidate = snapshot.candidates.firstOrNull { it.id == value.candidateId }
    val statusCandidate =
        when (val deployment = readCandidate(candidate)) {
          is ReadResult.Value -> deployment.value
          is ReadResult.Failure -> return deployment.result
        }
    return WorkflowResult.Success(
        StatusResponse(
            deployment = statusCandidate?.let(::StatusDeployment),
            release = StatusRelease(value),
            next = releaseNext(value),
        ),
    )
  }

  private fun readWorkspace(value: WorkspaceLookupResponse): StatusWorkspace {
    val content =
        if (contentPort == null) value.content
        else
            when (val result = contentPort.inspect(InspectContentRequest(value.workspace.id))) {
              is PortResult.Success -> result.value.content
              is PortResult.Failure -> value.content
            }
    val git =
        if (gitPort == null) null
        else
            when (val result = gitPort.inspect(GitInspectRequest(value.workspace.id))) {
              is PortResult.Success -> result.value.status
              is PortResult.Failure -> null
            }
    return StatusWorkspace(value.workspace, content, git)
  }

  private fun readValidation(workspace: StatusWorkspace?): ReadResult<List<Validation>> {
    if (workspace == null || validationPort == null || workspace.git == null)
        return ReadResult.Value(emptyList())
    return when (
        val result =
            validationPort.get(
                GetValidationRequest(
                    workspace.workspace.id,
                    workspace.git.revision,
                    workspace.git.fingerprint,
                )
            )
    ) {
      is PortResult.Success -> ReadResult.Value(result.value.validations)
      is PortResult.Failure ->
          ReadResult.Failure(
              failure(result.error, FailureCode.CHECK_NOT_PASSED, workspace.workspace.subTaskId)
          )
    }
  }

  private fun readCi(review: PullRequest?): ReadResult<CiStatus> {
    if (review == null || ciPort == null) return ReadResult.Value(review?.ci ?: CiStatus.PENDING)
    return when (
        val result =
            ciPort.get(
                GetCiRequest(
                    review.id,
                    review.changeRevision.providerRevision ?: review.changeRevision.diff.identity,
                )
            )
    ) {
      is PortResult.Success -> ReadResult.Value(result.value.status)
      is PortResult.Failure ->
          ReadResult.Failure(failure(result.error, FailureCode.EXTERNAL_FAILURE, review.id))
    }
  }

  private fun readAiReview(review: PullRequest?): ReadResult<AiReviewStatus> {
    if (review == null || aiReviewPort == null)
        return ReadResult.Value(review?.aiReview ?: AiReviewStatus.PENDING)
    return when (
        val result = aiReviewPort.get(GetAiReviewRequest(review.id, review.reviewRevision.id))
    ) {
      is PortResult.Success -> ReadResult.Value(result.value.status)
      is PortResult.Failure ->
          ReadResult.Failure(failure(result.error, FailureCode.EXTERNAL_FAILURE, review.id))
    }
  }

  private fun readQueue(
      subTaskId: SubTaskId,
      review: PullRequest?,
  ): ReadResult<List<MergeQueueEntry>> {
    if (mergeQueuePort == null) return ReadResult.Value(emptyList())
    return when (val result = mergeQueuePort.get(GetMergeQueueRequest(subTaskId, review?.id))) {
      is PortResult.Success -> ReadResult.Value(result.value.entries)
      is PortResult.Failure ->
          ReadResult.Failure(failure(result.error, FailureCode.MERGE_QUEUE_FAILED, subTaskId))
    }
  }

  private fun readCandidate(candidate: DeploymentCandidate?): ReadResult<DeploymentCandidate?> {
    if (candidate == null) return ReadResult.Value<DeploymentCandidate?>(null)
    if (deploymentPort == null) return ReadResult.Value(candidate)
    return when (val result = deploymentPort.getCandidate(GetCandidateRequest(candidate.id))) {
      is PortResult.Success -> ReadResult.Value<DeploymentCandidate?>(result.value.candidate)
      is PortResult.Failure ->
          ReadResult.Failure(failure(result.error, FailureCode.DEPLOYMENT_NOT_FOUND, candidate.id))
    }
  }

  private fun readRelease(candidate: DeploymentCandidate?): ReadResult<Release?> {
    if (candidate == null) return ReadResult.Value<Release?>(null)
    if (releasePort == null) return ReadResult.Value<Release?>(null)
    return when (val result = releasePort.get(GetReleaseRequest(candidateId = candidate.id))) {
      is PortResult.Success -> ReadResult.Value<Release?>(result.value.releases.firstOrNull())
      is PortResult.Failure ->
          ReadResult.Failure(failure(result.error, FailureCode.RELEASE_NOT_FOUND, candidate.id))
    }
  }

  private fun candidateFor(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
  ): DeploymentCandidate? = snapshot.candidates.firstOrNull { subTask.id in it.includedSubTasks }

  private fun blockers(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      review: PullRequest?,
      validations: List<Validation>,
      integration: Integration,
      candidate: DeploymentCandidate?,
  ): List<BlockedBy> {
    val blockers = mutableListOf<BlockedBy>()
    val dependency = subTask.requires
    if (subTask.state != SubTaskState.MERGED && dependency != null) {
      val parent = snapshot.subTasks.firstOrNull { it.id == dependency }
      if (parent?.state != SubTaskState.MERGED) {
        blockers +=
            BlockedBy(
                DEPENDENCY_NOT_MERGED,
                "direct dependency is not integrated into main",
                dependency,
            )
      }
    }
    if (review != null && subTask.state != SubTaskState.MERGED) {
      validations
          .filter { it.required && it.status != ValidationStatus.PASSED }
          .forEach {
            blockers += BlockedBy(VALIDATION_REQUIRED, "required validation has not passed", it.id)
          }
      if (review.ci != CiStatus.PASSED)
          blockers += BlockedBy(CI_NOT_PASSED, "required PR CI has not passed", subTask.id)
      if (review.aiReview == AiReviewStatus.FAILED)
          blockers += BlockedBy(AI_REVIEW_FAILED, "AI review failed", subTask.id)
      review.reviewRevision.openRequiredThreads.forEach {
        blockers +=
            BlockedBy(REQUIRED_THREAD_OPEN, "an unresolved [R] review thread remains", it.id)
      }
    }
    if (integration.mergeQueue?.state == MergeQueueState.FAILED) {
      blockers += BlockedBy(MERGE_QUEUE_FAILED, "merge queue validation failed", subTask.id)
    }
    if (candidate?.state == DeploymentCandidateState.FAILED) {
      blockers += BlockedBy(DEPLOYMENT_GATE_BLOCKED, "deployment candidate failed", candidate.id)
    }
    return blockers.distinctBy { Triple(it.code, it.target, it.message) }
  }

  private fun nextActions(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      review: PullRequest?,
      blockers: List<BlockedBy>,
      integration: Integration,
      candidate: DeploymentCandidate?,
      release: Release?,
  ): List<NextAction> {
    val next = mutableListOf<NextAction>()
    when {
      subTask.state == SubTaskState.DRAFT ->
          next +=
              NextAction(
                  ActorKind.HUMAN,
                  "ready_review",
                  "./tools/workflow gate ready ${subTask.id} --review-revision ${review?.reviewRevision?.id ?: "<revision>"}",
              )
      (subTask.state == SubTaskState.READY || subTask.state == SubTaskState.REVIEW) &&
          blockers.isEmpty() ->
          next +=
              NextAction(
                  ActorKind.HUMAN,
                  "approve_change",
                  "./tools/workflow gate approve ${subTask.id} --change-revision ${review?.changeRevision?.id ?: "<revision>"}",
              )
      subTask.state == SubTaskState.APPROVED &&
          blockers.isEmpty() &&
          integration.mergeQueue == null -> next += NextAction(ActorKind.WORKFLOW, "enqueue_merge")
    }
    if (subTask.state != SubTaskState.DRAFT && blockers.isNotEmpty()) {
      next += blockers.mapNotNull(::blockerNextAction)
    }
    if (candidate != null) next += candidateNext(candidate)
    if (release != null) next += releaseNext(release)
    return next.distinct()
  }

  private fun blockerNextAction(blocker: BlockedBy): NextAction? =
      when (blocker.code) {
        REQUIRED_THREAD_OPEN -> NextAction(ActorKind.AGENT, RESOLVE_REQUIRED_THREADS)
        VALIDATION_REQUIRED -> NextAction(ActorKind.AGENT, FIX_VALIDATION)
        CI_NOT_PASSED -> NextAction(ActorKind.WORKFLOW, AWAIT_CI)
        DEPENDENCY_NOT_MERGED -> NextAction(ActorKind.WORKFLOW, AWAIT_DEPENDENCY)
        MERGE_QUEUE_FAILED -> NextAction(ActorKind.AGENT, FIX_MERGE_QUEUE)
        else -> null
      }

  private fun candidateNext(candidate: DeploymentCandidate): List<NextAction> =
      when (candidate.state) {
        DeploymentCandidateState.CANDIDATE,
        DeploymentCandidateState.VALIDATING,
        -> listOf(NextAction(ActorKind.WORKFLOW, "validate_candidate"))
        DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL ->
            if (candidate.deployGateRequired)
                listOf(
                    NextAction(
                        ActorKind.HUMAN,
                        "approve_deployment",
                        "./tools/workflow gate deploy ${candidate.id}",
                    )
                )
            else listOf(NextAction(ActorKind.WORKFLOW, "start_canary"))
        DeploymentCandidateState.CANARY -> listOf(NextAction(ActorKind.WORKFLOW, "verify_canary"))
        DeploymentCandidateState.PRODUCTION -> emptyList()
        DeploymentCandidateState.FAILED -> listOf(NextAction(ActorKind.WORKFLOW, "retry_candidate"))
        DeploymentCandidateState.NOT_SELECTED -> emptyList()
      }

  private fun releaseNext(release: Release): List<NextAction> =
      io.springkit.workflow.domain
          .nextReleaseAction(release)
          ?.let {
            listOf(NextAction(it.actor, it.action, it.command))
          }
          .orEmpty()

  private fun failure(
      error: PortError,
      fallback: FailureCode,
      target: String? = error.target,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code = FailureCode.entries.firstOrNull { it.name == error.code } ?: fallback,
              message = error.message,
              blockedBy =
                  target?.let { listOf(BlockedBy(error.code, error.message, it)) }.orEmpty(),
          ),
      )

  private fun failureCode(
      code: FailureCode,
      message: String,
      target: String? = null,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code,
              message,
              target?.let { listOf(BlockedBy(code.name, message, it)) }.orEmpty(),
          )
      )

  private fun invalid(message: String): WorkflowResult.Failure =
      failureCode(FailureCode.INVALID_TARGET_SELECTION, message)

  private sealed interface ReadResult<out T> {
    data class Value<T>(val value: T) : ReadResult<T>

    data class Failure(val result: WorkflowResult.Failure) : ReadResult<Nothing>
  }
}
