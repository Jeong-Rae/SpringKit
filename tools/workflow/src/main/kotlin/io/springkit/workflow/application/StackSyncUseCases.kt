package io.springkit.workflow.application

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.BranchName
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureConflict
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.FailureWorkspace
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SyncConflict
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

/*
 * 관리 SubTask의 직접 의존성 하나를 변경합니다.
 */
data class StackRequest(
    val subTaskId: SubTaskId,
    val requires: SubTaskId? = null,
    val clear: Boolean = false,
    val expectedStoreRevision: String? = null,
)

data class StackResponse(
    val subTask: SubTask,
    val workspace: Workspace,
    val baseBranch: BranchName,
    val baseRevision: String,
    val pullRequest: PullRequest? = null,
    val diffIdentitySame: Boolean = true,
    val changeRevisionRetained: Boolean = true,
    val approvalRetained: Boolean = true,
    /*
     * WorkflowStoreSnapshot에 없어 다시 확인해야 하는 저장소 필드입니다.
     */
    val invalidatedState: List<String> = emptyList(),
)

/*
 * 관리 Worktree를 main 또는 직접 의존성과 동기화합니다.
 */
data class SyncRequest(
    val subTaskId: SubTaskId,
    val continueSync: Boolean = false,
    val abort: Boolean = false,
    val expectedStoreRevision: String? = null,
)

data class SyncConflictRecovery(
    val subTaskId: SubTaskId,
    val paths: List<String>,
    val canContinue: Boolean = true,
    val canAbort: Boolean = true,
)

data class SyncResponse(
    val subTask: SubTask,
    val workspace: Workspace,
    val baseBranch: BranchName,
    val baseRevision: String,
    val pullRequest: PullRequest? = null,
    val diffIdentitySame: Boolean = true,
    val changeRevisionRetained: Boolean = true,
    val approvalRetained: Boolean = true,
    val invalidatedState: List<String> = emptyList(),
    val recovery: SyncConflictRecovery? = null,
)

typealias StackCommand = StackRequest

typealias SyncCommand = SyncRequest

/*
 * stack과 sync 명령을 위한 애플리케이션 진입점입니다.
 */
class StackSyncUseCases(
    private val gitPort: GitPort,
    private val reviewPort: ReviewPort,
    private val storePort: WorkflowStorePort,
    private val idPort: IdPort? = null,
    private val compensationPort: CompensationPort? = null,
    private val clockPort: ClockPort? = null,
) {
  constructor(
      storePort: WorkflowStorePort,
      gitPort: GitPort,
      reviewPort: ReviewPort,
      idPort: IdPort? = null,
      compensationPort: CompensationPort? = null,
      clockPort: ClockPort? = null,
  ) : this(gitPort, reviewPort, storePort, idPort, compensationPort, clockPort)

  fun stack(request: StackRequest): WorkflowResult<StackResponse> = executeStack(request)

  fun execute(request: StackRequest): WorkflowResult<StackResponse> = executeStack(request)

  fun sync(request: SyncRequest): WorkflowResult<SyncResponse> = executeSync(request)

  fun execute(request: SyncRequest): WorkflowResult<SyncResponse> = executeSync(request)

  fun handle(request: StackRequest): WorkflowResult<StackResponse> = executeStack(request)

  fun handle(request: SyncRequest): WorkflowResult<SyncResponse> = executeSync(request)

  private fun executeStack(request: StackRequest): WorkflowResult<StackResponse> {
    if ((request.requires == null) == !request.clear) {
      return failure(
          FailureCode.INVALID_ARGUMENT,
          "stack requires exactly one of requires or clear",
      )
    }
    val snapshot = snapshot() ?: return lastFailure
    staleIfNeeded(request.expectedStoreRevision, snapshot.revision)?.let {
      return it
    }
    val target = findTarget(snapshot, request.subTaskId) ?: return lastFailure
    val desiredParent =
        if (request.clear) null
        else resolveDependency(snapshot, target.subTask, request.requires!!) ?: return lastFailure
    val base = resolveBase(snapshot, desiredParent) ?: return lastFailure
    if (target.subTask.requires == desiredParent.idOrNull()) {
      return WorkflowResult.Success(
          StackResponse(
              target.subTask,
              target.workspace,
              base.branch,
              base.revision,
              snapshot.pullRequests.firstOrNull { it.subTaskId == target.subTask.id },
          ),
      )
    }

    val childStatus = inspect(target.workspace) ?: return lastFailure
    val tx = transaction("stack:${request.subTaskId}:${desiredParent.idOrNull()}", snapshot)
    if (!begin(tx)) return lastFailure

    val changes = mutableListOf<ChangeReceipt>()
    val restackResult =
        gitPort.restack(
            RestackRequest(
                target.workspace.id,
                target.subTask.branch,
                base.branch,
                base.revision,
                childStatus.revision,
            ),
        )
    val restack =
        when (val result = restackResult) {
          is PortResult.Success -> {
            changes += result.value.change
            result.value
          }
          is PortResult.Failure -> {
            result.change?.let(changes::add)
            return recover(tx, changes, failureFrom(result.error))
          }
        }
    if (restack.conflicts.isNotEmpty()) {
      return conflict(
          tx,
          request.subTaskId,
          target.workspace.path,
          restack.conflicts,
          target.subTask,
          changes,
      )
    }

    val review = synchronizeReview(snapshot, target.subTask, base, restack.diffChanged, changes)
    if (review is PortResult.Failure) return recover(tx, changes, failureFrom(review.error))
    review as PortResult.Success
    val updatedSubTask = target.subTask.copy(requires = desiredParent.idOrNull())
    val updatedSnapshot =
        snapshot.copy(
            subTasks = snapshot.subTasks.replaceById(updatedSubTask) { it.id },
            dependencies =
                snapshot.dependencies.filterNot { it.subTaskId == updatedSubTask.id } +
                    listOfNotNull(
                        updatedSubTask.requires?.let { Dependency(updatedSubTask.id, it) }
                    ),
            pullRequests =
                snapshot.pullRequests.replaceByIdOrKeep(review.value.pullRequest) { it.id },
            syncConflicts = snapshot.syncConflicts - request.subTaskId,
        )
    if (!writeAndCommit(tx, snapshot, updatedSnapshot)) {
      return recover(tx, changes, lastFailure.data)
    }
    return WorkflowResult.Success(
        StackResponse(
            updatedSubTask,
            target.workspace,
            base.branch,
            base.revision,
            review.value.pullRequest,
            diffIdentitySame = !restack.diffChanged,
            changeRevisionRetained = !restack.diffChanged,
            approvalRetained = !restack.diffChanged,
            invalidatedState = invalidatedStateFor(restack.diffChanged),
        ),
    )
  }

  private fun executeSync(request: SyncRequest): WorkflowResult<SyncResponse> {
    if (request.continueSync && request.abort) {
      return failure(FailureCode.INVALID_ARGUMENT, "sync accepts at most one recovery action")
    }
    val snapshot = snapshot() ?: return lastFailure
    staleIfNeeded(request.expectedStoreRevision, snapshot.revision)?.let {
      return it
    }
    val pending = snapshot.syncConflicts[request.subTaskId]
    if (request.abort) return abortSync(request, snapshot)
    val target = findTarget(snapshot, request.subTaskId) ?: return lastFailure
    if (pending != null && !request.continueSync) {
      return conflictFailure(request.subTaskId, target.workspace.path, pending.conflictPaths)
    }
    if (request.continueSync && pending == null) {
      return failure(
          FailureCode.STATE_CONFLICT,
          "sync --continue is only available for an active SYNC_CONFLICT recovery",
      )
    }
    val parent =
        target.subTask.requires?.let { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    if (target.subTask.requires != null && parent == null) {
      return failure(
          FailureCode.DEPENDENCY_NOT_FOUND,
          "direct dependency is missing",
          target.subTask.requires,
      )
    }
    val base = resolveBase(snapshot, parent) ?: return lastFailure
    val childStatus = inspect(target.workspace) ?: return lastFailure
    if (childStatus.conflicts.isNotEmpty()) {
      return conflictFailure(request.subTaskId, target.workspace.path, childStatus.conflicts)
    }
    if (request.continueSync) {
      return continueAndPersist(
          snapshot,
          target,
          base,
          request.subTaskId,
          pending?.conflictPaths.orEmpty(),
      )
    }
    return restackAndPersist(snapshot, target, base, request.subTaskId)
  }

  private fun abortSync(
      request: SyncRequest,
      snapshot: WorkflowStoreSnapshot,
  ): WorkflowResult<SyncResponse> {
    if (snapshot.syncConflicts[request.subTaskId] == null) {
      return failure(
          FailureCode.STATE_CONFLICT,
          "sync --abort is only available for SYNC_CONFLICT",
      )
    }
    val target = findTarget(snapshot, request.subTaskId) ?: return lastFailure
    val parent =
        target.subTask.requires?.let { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    val base = resolveBase(snapshot, parent) ?: return lastFailure
    val aborted =
        when (val result = gitPort.abortRestack(AbortRestackRequest(target.workspace.id))) {
          is PortResult.Success -> result
          is PortResult.Failure -> return WorkflowResult.Failure(failureFrom(result.error))
        }
    val tx = transaction("sync-abort:${request.subTaskId}", snapshot)
    if (!begin(tx)) return lastFailure
    val updatedSnapshot = snapshot.copy(syncConflicts = snapshot.syncConflicts - request.subTaskId)
    if (!writeAndCommit(tx, snapshot, updatedSnapshot)) {
      return recover(tx, listOf(aborted.value.change), lastFailure.data)
    }
    return WorkflowResult.Success(
        SyncResponse(
            target.subTask,
            target.workspace,
            base.branch,
            base.revision,
            snapshot.pullRequests.firstOrNull { it.subTaskId == target.subTask.id },
            recovery = null,
        ),
        next = listOf(NextAction(ActorKind.AGENT, "check")),
    )
  }

  private fun continueAndPersist(
      snapshot: WorkflowStoreSnapshot,
      target: Target,
      base: Base,
      subTaskId: SubTaskId,
      previousConflictPaths: List<String>,
  ): WorkflowResult<SyncResponse> {
    val continued =
        when (val result = gitPort.continueRestack(ContinueRestackRequest(target.workspace.id))) {
          is PortResult.Success -> result
          is PortResult.Failure ->
              return if (result.error.code.uppercase() == "SYNC_CONFLICT") {
                conflictFailure(
                    subTaskId,
                    target.workspace.path,
                    (previousConflictPaths + conflictPaths(target.workspace, result.error))
                        .distinct(),
                )
              } else {
                WorkflowResult.Failure(failureFrom(result.error))
              }
        }
    val status = inspect(target.workspace) ?: return lastFailure
    if (status.conflicts.isNotEmpty()) {
      return conflictFailure(subTaskId, target.workspace.path, status.conflicts)
    }
    val tx = transaction("sync-continue:$subTaskId", snapshot)
    if (!begin(tx)) return lastFailure
    val changes = mutableListOf(continued.value.change)
    val review = synchronizeReview(snapshot, target.subTask, base, true, changes)
    if (review is PortResult.Failure) return recover(tx, changes, failureFrom(review.error))
    review as PortResult.Success
    val parent =
        target.subTask.requires?.let { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    val parentMerged =
        parent != null &&
            (parent.state == io.springkit.workflow.domain.SubTaskState.MERGED ||
                snapshot.integrations.any {
                  it.subTaskId == parent.id && it.state == IntegrationState.MERGED
                })
    val updatedSubTask = if (parentMerged) target.subTask.copy(requires = null) else target.subTask
    val updatedSnapshot =
        snapshot.copy(
            subTasks = snapshot.subTasks.replaceById(updatedSubTask) { it.id },
            dependencies =
                snapshot.dependencies.filterNot { it.subTaskId == updatedSubTask.id } +
                    listOfNotNull(
                        updatedSubTask.requires?.let { Dependency(updatedSubTask.id, it) }
                    ),
            pullRequests =
                snapshot.pullRequests.replaceByIdOrKeep(review.value.pullRequest) { it.id },
            syncConflicts = snapshot.syncConflicts - subTaskId,
        )
    if (!writeAndCommit(tx, snapshot, updatedSnapshot)) {
      return recover(tx, changes, lastFailure.data)
    }
    return WorkflowResult.Success(
        SyncResponse(
            updatedSubTask,
            target.workspace,
            base.branch,
            base.revision,
            review.value.pullRequest,
            diffIdentitySame = false,
            changeRevisionRetained = false,
            approvalRetained = false,
            invalidatedState = invalidatedStateFor(true),
        ),
        next = listOf(NextAction(ActorKind.AGENT, "check")),
    )
  }

  private fun restackAndPersist(
      snapshot: WorkflowStoreSnapshot,
      target: Target,
      base: Base,
      subTaskId: SubTaskId,
  ): WorkflowResult<SyncResponse> {
    val status = inspect(target.workspace) ?: return lastFailure
    val tx = transaction("sync:$subTaskId", snapshot)
    if (!begin(tx)) return lastFailure
    val changes = mutableListOf<ChangeReceipt>()
    val restackResult =
        gitPort.restack(
            RestackRequest(
                target.workspace.id,
                target.subTask.branch,
                base.branch,
                base.revision,
                status.revision,
            ),
        )
    val restack =
        when (val result = restackResult) {
          is PortResult.Success -> {
            changes += result.value.change
            result.value
          }
          is PortResult.Failure -> {
            result.change?.let(changes::add)
            return if (result.error.code.uppercase() == "SYNC_CONFLICT") {
              conflict(
                  tx,
                  subTaskId,
                  target.workspace.path,
                  conflictPaths(target.workspace, result.error),
                  target.subTask,
                  changes,
              )
            } else recover(tx, changes, failureFrom(result.error))
          }
        }
    if (restack.conflicts.isNotEmpty()) {
      return conflict(
          tx,
          subTaskId,
          target.workspace.path,
          restack.conflicts,
          target.subTask,
          changes,
      )
    }
    val review = synchronizeReview(snapshot, target.subTask, base, restack.diffChanged, changes)
    if (review is PortResult.Failure) return recover(tx, changes, failureFrom(review.error))
    review as PortResult.Success
    val parent =
        target.subTask.requires?.let { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    val parentMerged =
        parent != null &&
            (parent.state == io.springkit.workflow.domain.SubTaskState.MERGED ||
                snapshot.integrations.any {
                  it.subTaskId == parent.id && it.state == IntegrationState.MERGED
                })
    val updatedSubTask = if (parentMerged) target.subTask.copy(requires = null) else target.subTask
    val updatedSnapshot =
        snapshot.copy(
            subTasks = snapshot.subTasks.replaceById(updatedSubTask) { it.id },
            dependencies =
                snapshot.dependencies.filterNot { it.subTaskId == updatedSubTask.id } +
                    listOfNotNull(
                        updatedSubTask.requires?.let { Dependency(updatedSubTask.id, it) }
                    ),
            pullRequests =
                snapshot.pullRequests.replaceByIdOrKeep(review.value.pullRequest) { it.id },
            syncConflicts = snapshot.syncConflicts - subTaskId,
        )
    if (!writeAndCommit(tx, snapshot, updatedSnapshot))
        return recover(tx, changes, lastFailure.data)
    return WorkflowResult.Success(
        SyncResponse(
            updatedSubTask,
            target.workspace,
            base.branch,
            base.revision,
            review.value.pullRequest,
            diffIdentitySame = !restack.diffChanged,
            changeRevisionRetained = !restack.diffChanged,
            approvalRetained = !restack.diffChanged,
            invalidatedState = invalidatedStateFor(restack.diffChanged),
        ),
        next = listOf(NextAction(ActorKind.AGENT, "check")),
    )
  }

  private fun synchronizeReview(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      base: Base,
      diffChanged: Boolean,
      changes: MutableList<ChangeReceipt>,
  ): PortResult<ReviewSync> {
    val stored = snapshot.pullRequests.firstOrNull { it.subTaskId == subTask.id }
    if (stored == null || subTask.pullRequestId == null) {
      return PortResult.Success(ReviewSync(null, true))
    }
    val current =
        when (val result = reviewPort.get(GetReviewRequest(stored.id))) {
          is PortResult.Success -> result.value.pullRequest
          is PortResult.Failure -> return result
        }
    val nextChange =
        if (!diffChanged) current.changeRevision
        else newChangeRevision(current.changeRevision, subTask.id)
    val updatedResult =
        reviewPort.update(
            UpdateReviewRequest(
                pullRequestId = current.id,
                expectedReviewRevisionId = current.reviewRevision.id,
                body = current.body,
                base = base.branch,
                changeRevision = if (diffChanged) nextChange else null,
            ),
        )
    val updated =
        when (val result = updatedResult) {
          is PortResult.Success -> {
            changes += result.value.change
            result.value
          }
          is PortResult.Failure -> return result
        }
    val normalized =
        if (diffChanged) {
          updated.pullRequest.copy(
              base = base.branch,
              changeRevision = nextChange,
              approval = null,
              ci = io.springkit.workflow.domain.CiStatus.PENDING,
              aiReview = io.springkit.workflow.domain.AiReviewStatus.PENDING,
          )
        } else {
          updated.pullRequest.copy(
              base = base.branch,
              changeRevision = current.changeRevision,
              approval = current.approval,
              ci = current.ci,
              aiReview = current.aiReview,
          )
        }
    return PortResult.Success(ReviewSync(normalized, !diffChanged))
  }

  private fun newChangeRevision(before: ChangeRevision, subTaskId: SubTaskId): ChangeRevision {
    val id =
        when (
            val result = idPort?.issue(IssueIdRequest(IdKind.CHANGE_REVISION, "sync:$subTaskId"))
        ) {
          is PortResult.Success -> result.value.ids.firstOrNull()?.value
          else -> null
        } ?: "cr-${subTaskId}-${before.number + 1}"
    val identity = "${before.diff.identity}:restack:${before.number + 1}"
    return before.copy(
        id = id,
        number = before.number + 1,
        diff = before.diff.copy(identity = identity),
    )
  }

  private fun resolveDependency(
      snapshot: WorkflowStoreSnapshot,
      target: SubTask,
      parentId: SubTaskId,
  ): SubTask? {
    val parent =
        snapshot.subTasks.firstOrNull { it.id == parentId }
            ?: return fail(
                FailureCode.DEPENDENCY_NOT_FOUND,
                "direct dependency does not exist",
                parentId,
            )
    if (parent.id == target.id || parent.taskId != target.taskId) {
      return fail(FailureCode.INVALID_ARGUMENT, "dependency must belong to the same task", parentId)
    }
    if (
        parent.state == io.springkit.workflow.domain.SubTaskState.MERGED ||
            snapshot.integrations.any {
              it.subTaskId == parent.id && it.state == IntegrationState.MERGED
            }
    ) {
      return fail(
          FailureCode.DEPENDENCY_NOT_MERGED,
          "a merged subtask cannot be a stack parent",
          parentId,
      )
    }
    val dependencies =
        snapshot.dependencies.filterNot { it.subTaskId == target.id } +
            Dependency(target.id, parent.id)
    if (io.springkit.workflow.domain.WorkflowRules.hasDependencyCycle(dependencies)) {
      return fail(FailureCode.DEPENDENCY_CYCLE, "stack dependency would create a cycle", parentId)
    }
    var current: SubTask? = parent
    val visited = mutableSetOf<SubTaskId>()
    while (current != null && visited.add(current.id)) {
      if (current.requires == target.id) {
        return fail(FailureCode.DEPENDENCY_CYCLE, "stack dependency would create a cycle", parentId)
      }
      current = current.requires?.let { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    }
    return parent
  }

  private fun resolveBase(
      snapshot: WorkflowStoreSnapshot,
      parent: SubTask?,
  ): Base? {
    if (parent == null) {
      return when (val result = gitPort.refreshMain(MainRevisionRequest())) {
        is PortResult.Success -> Base("main", result.value.revision)
        is PortResult.Failure -> fail(FailureCode.EXTERNAL_FAILURE, result.error.message, "main")
      }
    }
    val integration = snapshot.integrations.firstOrNull { it.subTaskId == parent.id }
    if (
        parent.state == io.springkit.workflow.domain.SubTaskState.MERGED ||
            integration?.state == IntegrationState.MERGED
    ) {
      return when (val result = gitPort.refreshMain(MainRevisionRequest())) {
        is PortResult.Success -> Base("main", result.value.revision)
        is PortResult.Failure -> fail(FailureCode.EXTERNAL_FAILURE, result.error.message, "main")
      }
    }
    val workspace =
        parent.workspace ?: snapshot.workspaces.firstOrNull { it.subTaskId == parent.id }
    if (workspace == null || !workspace.managed) {
      return fail(FailureCode.WORKTREE_REQUIRED, "dependency worktree is not managed", parent.id)
    }
    return when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
      is PortResult.Success -> Base(parent.branch, result.value.status.revision)
      is PortResult.Failure -> fail(FailureCode.EXTERNAL_FAILURE, result.error.message, parent.id)
    }
  }

  private fun findTarget(snapshot: WorkflowStoreSnapshot, id: SubTaskId): Target? {
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == id }
            ?: return fail(FailureCode.SUBTASK_NOT_FOUND, "subtask does not exist", id)
    val workspace = subTask.workspace ?: snapshot.workspaces.firstOrNull { it.subTaskId == id }
    if (workspace == null || !workspace.managed) {
      return fail(FailureCode.WORKTREE_REQUIRED, "subtask does not have a managed worktree", id)
    }
    return Target(subTask, workspace)
  }

  private fun inspect(workspace: Workspace): GitStatus? =
      when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
        is PortResult.Success -> result.value.status
        is PortResult.Failure ->
            fail(FailureCode.EXTERNAL_FAILURE, result.error.message, workspace.id)
      }

  private fun snapshot(): WorkflowStoreSnapshot? =
      when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
        is PortResult.Success -> result.value.snapshot
        is PortResult.Failure ->
            fail(FailureCode.STORE_FAILURE, result.error.message, result.error.target)
      }

  private fun transaction(operation: String, snapshot: WorkflowStoreSnapshot) =
      StoreTransactionRequest("tx-$operation", snapshot.revision, "$operation:${snapshot.revision}")

  private fun begin(transaction: StoreTransactionRequest): Boolean =
      when (val result = storePort.begin(transaction)) {
        is PortResult.Success ->
            if (result.value.state == StoreTransactionState.OPEN) true
            else {
              failure(FailureCode.STATE_CONFLICT, "store transaction is not open")
              false
            }
        is PortResult.Failure -> {
          failure(FailureCode.STORE_FAILURE, result.error.message, result.error.target)
          false
        }
      }

  private fun writeAndCommit(
      transaction: StoreTransactionRequest,
      before: WorkflowStoreSnapshot,
      after: WorkflowStoreSnapshot,
  ): Boolean {
    when (
        val write =
            storePort.write(StoreWriteRequest(transaction.transactionId, before.revision, after))
    ) {
      is PortResult.Failure -> {
        lastFailure = WorkflowResult.Failure(failureFrom(write.error))
        return false
      }
      is PortResult.Success -> Unit
    }
    return when (val commit = storePort.commit(transaction)) {
      is PortResult.Success -> true
      is PortResult.Failure -> {
        lastFailure = WorkflowResult.Failure(failureFrom(commit.error))
        false
      }
    }
  }

  private fun conflict(
      transaction: StoreTransactionRequest,
      subTaskId: SubTaskId,
      workspacePath: WorkspacePath,
      paths: List<String>,
      before: SubTask,
      changes: List<ChangeReceipt>,
  ): WorkflowResult.Failure {
    storePort.rollback(transaction)
    val conflictSnapshot = snapshot() ?: return lastFailure
    val conflictTransaction = transaction("sync-conflict:$subTaskId", conflictSnapshot)
    if (!begin(conflictTransaction)) return lastFailure
    val conflict =
        SyncConflict(
            subTaskId = subTaskId,
            before = before,
            conflictPaths = paths.distinct(),
            startedAtEpochMillis = now("sync-conflict:$subTaskId"),
        )
    val updatedSnapshot =
        conflictSnapshot.copy(
            syncConflicts = conflictSnapshot.syncConflicts + (subTaskId to conflict)
        )
    if (!writeAndCommit(conflictTransaction, conflictSnapshot, updatedSnapshot)) {
      return recover(conflictTransaction, emptyList(), lastFailure.data)
    }
    return conflictFailure(subTaskId, workspacePath, paths)
  }

  private fun conflictFailure(
      subTaskId: SubTaskId,
      workspacePath: WorkspacePath,
      paths: List<String>,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              FailureCode.SYNC_CONFLICT,
              "automatic synchronization has unresolved conflicts",
              blockedBy =
                  paths.distinct().map {
                    BlockedBy("SYNC_CONFLICT", "conflict requires resolution", it)
                  },
              next =
                  listOf(
                      NextAction(ActorKind.AGENT, "resolve_conflicts"),
                      NextAction(
                          ActorKind.AGENT,
                          "continue_sync",
                          "./tools/workflow/bin/workflow sync --continue",
                      ),
                      NextAction(
                          ActorKind.AGENT,
                          "abort_sync",
                          "./tools/workflow/bin/workflow sync --abort",
                      ),
                  ),
              workspace = FailureWorkspace(workspacePath),
              conflicts = paths.distinct().map { FailureConflict(it) },
          ),
      )

  private fun conflictPaths(workspace: Workspace, error: PortError): List<String> =
      listOfNotNull(error.target).ifEmpty { listOf("${workspace.path.value}:unknown") }

  private fun recover(
      transaction: StoreTransactionRequest,
      changes: List<ChangeReceipt>,
      data: FailureData,
  ): WorkflowResult.Failure {
    val rollback = storePort.rollback(transaction)
    val failures = compensate(changes, data.message)
    val all = listOfNotNull((rollback as? PortResult.Failure)?.error) + failures
    return if (all.isEmpty()) WorkflowResult.Failure(data)
    else
        WorkflowResult.Failure(
            data.copy(
                blockedBy =
                    data.blockedBy +
                        BlockedBy("COMPENSATION_REQUIRED", all.joinToString { it.message }),
                next = data.next + NextAction(ActorKind.WORKFLOW, "reconcile_sync"),
            ),
        )
  }

  private fun compensate(changes: List<ChangeReceipt>, reason: String): List<PortError> =
      changes.asReversed().mapNotNull { change ->
        val compensation = change.compensation ?: return@mapNotNull null
        val port =
            compensationPort
                ?: return@mapNotNull PortError(
                    "COMPENSATION_PORT_UNAVAILABLE",
                    "compensation port is not configured",
                )
        when (val result = port.compensate(CompensateRequest(change, reason))) {
          is PortResult.Success -> null
          is PortResult.Failure -> result.error
        }
      }

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null -> 0L
      }

  private fun failureFrom(error: PortError, target: String? = error.target): FailureData =
      FailureData(
          code = mapFailure(error.code),
          message = error.message,
          blockedBy = listOfNotNull(target?.let { BlockedBy(error.code, error.message, it) }),
          next = if (error.retryable) listOf(NextAction(ActorKind.AGENT, "retry")) else emptyList(),
      )

  private fun staleIfNeeded(expected: String?, actual: String): WorkflowResult.Failure? =
      if (expected != null && expected != actual)
          WorkflowResult.Failure(
              FailureData(
                  FailureCode.STALE_REVISION,
                  "workflow store revision is stale",
                  blockedBy =
                      listOf(
                          BlockedBy(
                              "STALE_REVISION",
                              "expected $expected but found $actual",
                              actual,
                          )
                      ),
              )
          )
      else null

  private fun failure(
      code: FailureCode,
      message: String,
      target: String? = null,
      next: List<NextAction> = emptyList(),
  ): WorkflowResult.Failure {
    lastFailure =
        WorkflowResult.Failure(
            FailureData(
                code,
                message,
                listOfNotNull(target?.let { BlockedBy(code.name, message, it) }),
                next,
            )
        )
    return lastFailure
  }

  private fun <T> fail(code: FailureCode, message: String, target: String? = null): T? {
    failure(code, message, target)
    return null
  }

  private fun mapFailure(code: String): FailureCode =
      runCatching { FailureCode.valueOf(code.uppercase()) }
          .getOrDefault(
              when (code.uppercase()) {
                "CONFLICT",
                "MERGE_CONFLICT" -> FailureCode.SYNC_CONFLICT
                "REVISION_CONFLICT",
                "CONCURRENT_MODIFICATION" -> FailureCode.STALE_REVISION
                else -> FailureCode.EXTERNAL_FAILURE
              },
          )

  private fun invalidatedStateFor(diffChanged: Boolean): List<String> =
      if (diffChanged) {
        listOf("change_revision", "approval", "ci", "ai_review", "validation")
      } else emptyList()

  private data class Base(val branch: BranchName, val revision: String)

  private data class Target(val subTask: SubTask, val workspace: Workspace)

  private data class ReviewSync(val pullRequest: PullRequest?, val same: Boolean)

  private var lastFailure: WorkflowResult.Failure =
      WorkflowResult.Failure(FailureData(FailureCode.STATE_CONFLICT, "workflow operation failed"))
}

class StackUseCase(
    gitPort: GitPort,
    reviewPort: ReviewPort,
    storePort: WorkflowStorePort,
    idPort: IdPort? = null,
    compensationPort: CompensationPort? = null,
    clockPort: ClockPort? = null,
) {
  private val delegate =
      StackSyncUseCases(gitPort, reviewPort, storePort, idPort, compensationPort, clockPort)

  fun execute(request: StackRequest): WorkflowResult<StackResponse> = delegate.execute(request)

  fun stack(request: StackRequest): WorkflowResult<StackResponse> = delegate.stack(request)
}

class SyncUseCase(
    gitPort: GitPort,
    reviewPort: ReviewPort,
    storePort: WorkflowStorePort,
    idPort: IdPort? = null,
    compensationPort: CompensationPort? = null,
    clockPort: ClockPort? = null,
) {
  private val delegate =
      StackSyncUseCases(gitPort, reviewPort, storePort, idPort, compensationPort, clockPort)

  fun execute(request: SyncRequest): WorkflowResult<SyncResponse> = delegate.execute(request)

  fun sync(request: SyncRequest): WorkflowResult<SyncResponse> = delegate.sync(request)
}

private fun SubTask?.idOrNull(): SubTaskId? = this?.id

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> =
    filterNot { id(it) == id(value) } + value

private fun <T> List<T>.replaceByIdOrKeep(value: T?, id: (T) -> String): List<T> =
    if (value == null) this else replaceById(value, id)
