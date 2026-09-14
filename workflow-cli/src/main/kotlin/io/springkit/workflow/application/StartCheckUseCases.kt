package io.springkit.workflow.application

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskStarted
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskState
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspaceId
import io.springkit.workflow.domain.WorkspacePath

/** Input for the provider-independent start application service. */
data class StartRequest(
    val taskId: io.springkit.workflow.domain.ExternalTaskId,
    val requestId: String,
    val title: String,
    val requires: SubTaskId? = null,
    val workspacePath: WorkspacePath? = null,
    val expectedStoreRevision: String? = null,
) {
  init {
    require(requestId.isNotBlank()) { "start request id must not be blank" }
    require(title.isNotBlank()) { "start title must not be blank" }
  }

  val externalTaskId: io.springkit.workflow.domain.ExternalTaskId
    get() = taskId
}

/** Result returned after a SubTask, Branch and managed Worktree have been created. */
data class StartResponse(
    val task: Task,
    val subTask: SubTask,
    val workspace: Workspace,
    val baseBranch: String,
    val baseRevision: String,
    val idempotent: Boolean = false,
)

/** Input for checking one managed Worktree. */
data class CheckRequest(
    val workspaceId: WorkspaceId? = null,
    val subTaskId: SubTaskId? = null,
    val workspacePath: WorkspacePath? = null,
    val expectedStoreRevision: String? = null,
)

/** The check result is publishable only when every check applies to the final content. */
data class CheckResponse(
    val workspace: Workspace,
    val revision: String,
    val fingerprint: String,
    val validations: List<Validation>,
    val summary: CheckSummary,
    val publishable: Boolean,
)

typealias StartCommand = StartRequest

typealias CheckCommand = CheckRequest

/**
 * Application service for wf-02. It deliberately knows only domain objects and outbound ports.
 * Adapters own provider-specific idempotency, filesystem and process details.
 */
class StartUseCase(
    private val taskPort: TaskPort,
    private val gitPort: GitPort,
    private val workspacePort: WorkspacePort,
    private val storePort: WorkflowStorePort,
    private val idPort: IdPort? = null,
    private val compensationPort: CompensationPort? = null,
    private val clockPort: ClockPort? = null,
) {
  fun start(request: StartRequest): WorkflowResult<StartResponse> = execute(request)

  fun handle(request: StartRequest): WorkflowResult<StartResponse> = execute(request)

  fun execute(request: StartRequest): WorkflowResult<StartResponse> {
    val snapshotResult = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))
    val snapshot =
        when (snapshotResult) {
          is PortResult.Success -> snapshotResult.value.snapshot
          is PortResult.Failure -> return failure(snapshotResult.error)
        }
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return stale("workflow store revision is stale", snapshot.revision)
    }

    val taskResult = taskPort.get(TaskLookupRequest(externalId = request.taskId))
    val task =
        when (taskResult) {
          is PortResult.Success -> taskResult.value.task
          is PortResult.Failure -> return failure(taskResult.error)
        }
    if (task.externalId != request.taskId) {
      return WorkflowResult.Failure(invalid("task adapter returned a different external task"))
    }

    val startKey =
        io.springkit.workflow.domain.StartRequestKey(request.taskId.value, request.requestId)
    val previousStart = snapshot.startRequests[startKey]
    if (previousStart != null) {
      if (previousStart.title != request.title || previousStart.requires != request.requires) {
        return WorkflowResult.Failure(
            idempotencyConflict("start request metadata differs from the original request")
        )
      }
      val previousSubTask = snapshot.subTasks.firstOrNull { it.id == previousStart.subTaskId }
      val previousWorkspace =
          snapshot.workspaces.firstOrNull { it.subTaskId == previousStart.subTaskId }
              ?: previousSubTask?.workspace
      if (previousSubTask == null || previousWorkspace == null) {
        return stateConflict("start request refers to incomplete workflow state")
      }
      return WorkflowResult.Success(
          StartResponse(
              task = snapshot.tasks.firstOrNull { it.id == previousSubTask.taskId } ?: task,
              subTask = previousSubTask,
              workspace = previousWorkspace,
              baseBranch = previousSubTask.requires ?: "main",
              baseRevision = snapshot.revision,
              idempotent = true,
          )
      )
    }

    val parent =
        if (request.requires == null) {
          null
        } else {
          when (val result = taskPort.getSubTask(SubTaskLookupRequest(request.requires))) {
            is PortResult.Success -> result.value.subTask
            is PortResult.Failure -> return failure(result.error, target = request.requires)
          }
        }
    if (parent != null && parent.taskId != task.id) {
      return WorkflowResult.Failure(
          invalid("a dependency must belong to the same task", request.requires)
      )
    }

    val base =
        if (parent == null) {
          when (val result = gitPort.refreshMain(MainRevisionRequest())) {
            is PortResult.Success -> Base("main", result.value.revision)
            is PortResult.Failure -> return failure(result.error, target = "main")
          }
        } else {
          val parentWorkspace = parent.workspace
          if (parentWorkspace == null || !parentWorkspace.managed) {
            return WorkflowResult.Failure(
                io.springkit.workflow.domain.FailureData(
                    FailureCode.WORKTREE_REQUIRED,
                    "the direct dependency must have a managed worktree",
                    blockedBy =
                        listOf(
                            BlockedBy(
                                "WORKTREE_REQUIRED",
                                "dependency worktree is not available",
                                parent.id,
                            ),
                        ),
                ),
            )
          }
          when (val result = gitPort.inspect(GitInspectRequest(parentWorkspace.id))) {
            is PortResult.Success -> Base(parent.branch, result.value.status.revision)
            is PortResult.Failure -> return failure(result.error, target = parent.id)
          }
        }

    val transactionId = issueId(IdKind.TRANSACTION, "start:${request.requestId}")
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = transactionId,
            expectedRevision = snapshot.revision,
            idempotencyKey = request.requestId,
        )
    when (val result = storePort.begin(transactionRequest)) {
      is PortResult.Success ->
          if (result.value.state != StoreTransactionState.OPEN) {
            return stateConflict("start transaction is not open")
          }
      is PortResult.Failure -> return failure(result.error)
    }

    val changes = mutableListOf<ChangeReceipt>()
    var lastPortFailure: PortError? = null
    fun <T : ChangeResponse> remember(result: PortResult<T>): T? =
        when (result) {
          is PortResult.Success -> {
            changes += result.value.change
            result.value
          }
          is PortResult.Failure -> {
            lastPortFailure = result.error
            result.change?.let(changes::add)
            null
          }
        }

    val createdSubTask =
        remember(
            taskPort.createSubTask(
                CreateSubTaskRequest(
                    externalTaskId = request.taskId,
                    title = request.title,
                    requestId = request.requestId,
                    requires = request.requires,
                ),
            ),
        )
            ?: return recover(
                transactionRequest,
                changes,
                failureData(
                    portFailure(
                        lastPortFailure ?: taskPort.createSubTaskFailure(request),
                        "create subtask",
                    ),
                ),
            )

    val subTask = createdSubTask.subTask
    val existingSubTask = snapshot.subTasks.firstOrNull { it.id == subTask.id }
    if (
        existingSubTask != null &&
            (existingSubTask.title != request.title || existingSubTask.requires != request.requires)
    ) {
      return recover(
          transactionRequest,
          changes,
          idempotencyConflict("start request metadata differs from the existing subtask"),
      )
    }
    if (
        subTask.taskId != task.id ||
            subTask.title != request.title ||
            subTask.requires != request.requires
    ) {
      return recover(
          transactionRequest,
          changes,
          invalid("subtask metadata does not match the start request"),
      )
    }
    val existingWorkspace =
        snapshot.workspaces.firstOrNull { it.subTaskId == subTask.id } ?: existingSubTask?.workspace
    if (existingSubTask != null && existingWorkspace != null) {
      when (val result = storePort.rollback(transactionRequest)) {
        is PortResult.Success ->
            return WorkflowResult.Success(
                StartResponse(
                    task = snapshot.tasks.firstOrNull { it.id == task.id } ?: task,
                    subTask = existingSubTask,
                    workspace = existingWorkspace,
                    baseBranch = existingSubTask.requires ?: "main",
                    baseRevision = snapshot.revision,
                    idempotent = true,
                ),
            )
        is PortResult.Failure -> return failure(result.error)
      }
    }
    val workspaceId = issueId(IdKind.WORKSPACE, "start:${request.requestId}:workspace")
    val path = request.workspacePath ?: WorkspacePath("workspaces/${subTask.id}")

    val branch =
        remember(
            gitPort.createBranch(
                CreateBranchRequest(
                    branch = subTask.branch,
                    baseBranch = base.branch,
                    baseRevision = base.revision,
                ),
            ),
        )
            ?: return recover(
                transactionRequest,
                changes,
                failureData(
                    portFailure(
                        lastPortFailure ?: gitPort.createBranchFailure(subTask, base),
                        "create branch",
                    ),
                ),
            )
    if (branch.branch != subTask.branch || branch.revision.isBlank()) {
      return recover(
          transactionRequest,
          changes,
          invalid("branch adapter returned invalid metadata"),
      )
    }

    val worktree =
        remember(
            gitPort.createWorktree(
                CreateWorktreeRequest(workspaceId, subTask.branch, path),
            ),
        )
            ?: return recover(
                transactionRequest,
                changes,
                failureData(
                    portFailure(
                        lastPortFailure ?: gitPort.createWorktreeFailure(workspaceId),
                        "create worktree",
                    ),
                ),
            )
    if (worktree.path != path) {
      return recover(
          transactionRequest,
          changes,
          invalid("worktree adapter returned a different path"),
      )
    }

    val workspace =
        remember(
                workspacePort.create(
                    CreateWorkspaceRequest(
                        workspaceId = workspaceId,
                        subTaskId = subTask.id,
                        branch = subTask.branch,
                        baseRevision = base.revision,
                        path = path,
                    ),
                ),
            )
            ?.workspace
            ?: return recover(
                transactionRequest,
                changes,
                failureData(
                    portFailure(
                        lastPortFailure ?: workspacePort.createFailure(workspaceId),
                        "record workspace",
                    ),
                ),
            )
    if (
        !workspace.managed ||
            workspace.subTaskId != subTask.id ||
            workspace.branch != subTask.branch
    ) {
      return recover(transactionRequest, changes, invalid("workspace metadata is not managed"))
    }

    val storedTask =
        task.copy(
            state = if (task.state == TaskState.OPEN) TaskState.IN_PROGRESS else task.state,
            subTaskIds = (task.subTaskIds + subTask.id).distinct(),
        )
    val storedSnapshot =
        snapshot.copy(
            tasks = snapshot.tasks.replaceById(storedTask) { it.id },
            subTasks = snapshot.subTasks.replaceById(subTask) { it.id },
            dependencies =
                snapshot.dependencies.filterNot { it.subTaskId == subTask.id } +
                    listOfNotNull(request.requires?.let { Dependency(subTask.id, it) }),
            workspaces = snapshot.workspaces.replaceById(workspace) { it.id },
            startRequests =
                snapshot.startRequests +
                    (startKey to
                        io.springkit.workflow.domain.StartRequestRecord(
                            key = startKey,
                            subTaskId = subTask.id,
                            title = request.title,
                            requires = request.requires,
                        )),
        )
    when (
        val result =
            storePort.write(StoreWriteRequest(transactionId, snapshot.revision, storedSnapshot))
    ) {
      is PortResult.Success -> Unit
      is PortResult.Failure -> {
        result.change?.let(changes::add)
        return recover(transactionRequest, changes, failureData(result.error))
      }
    }
    val occurredAt = now("start:${request.requestId}")
    when (
        val result =
            storePort.append(
                StoreEventRequest(
                    transactionId,
                    SubTaskStarted(subTask.id, task.id, occurredAt),
                ),
            )
    ) {
      is PortResult.Success -> Unit
      is PortResult.Failure -> {
        result.change?.let(changes::add)
        return recover(transactionRequest, changes, failureData(result.error))
      }
    }
    when (val result = storePort.commit(transactionRequest)) {
      is PortResult.Success -> Unit
      is PortResult.Failure ->
          return recover(transactionRequest, changes, failureData(result.error))
    }

    return WorkflowResult.Success(
        StartResponse(
            task = storedTask,
            subTask = subTask,
            workspace = workspace,
            baseBranch = base.branch,
            baseRevision = base.revision,
        ),
        next = listOf(NextAction(io.springkit.workflow.domain.ActorKind.AGENT, "implement_change")),
    )
  }

  private fun issueId(kind: IdKind, requestId: String): String =
      when (val result = idPort?.issue(IssueIdRequest(kind, requestId))) {
        is PortResult.Success ->
            result.value.ids.firstOrNull()?.value ?: fallbackId(kind, requestId)
        is PortResult.Failure,
        null -> fallbackId(kind, requestId)
      }

  private fun fallbackId(kind: IdKind, requestId: String): String =
      when (kind) {
        IdKind.TRANSACTION -> "tx-$requestId"
        IdKind.WORKSPACE -> "ws-$requestId"
        else -> "$requestId-${kind.name.lowercase()}"
      }

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null -> 0L
      }

  private fun recover(
      transaction: StoreTransactionRequest,
      changes: List<ChangeReceipt>,
      data: io.springkit.workflow.domain.FailureData,
  ): WorkflowResult.Failure {
    val rollback = storePort.rollback(transaction)
    val rollbackFailure = (rollback as? PortResult.Failure)?.error
    val compensationFailures = compensate(changes, data.message)
    val failures = listOfNotNull(rollbackFailure) + compensationFailures
    return if (failures.isEmpty()) {
      WorkflowResult.Failure(data)
    } else {
      WorkflowResult.Failure(
          data.copy(
              blockedBy =
                  data.blockedBy +
                      BlockedBy(
                          "COMPENSATION_REQUIRED",
                          failures.joinToString("; ") { it.message },
                      ),
              next =
                  data.next +
                      NextAction(
                          io.springkit.workflow.domain.ActorKind.WORKFLOW,
                          "reconcile_start",
                      ),
          ),
      )
    }
  }

  private fun compensate(changes: List<ChangeReceipt>, reason: String): List<PortError> =
      changes.asReversed().mapNotNull { change ->
        if (change.compensation == null) return@mapNotNull null
        val port = compensationPort
        if (port == null) {
          return@mapNotNull PortError(
              "COMPENSATION_PORT_UNAVAILABLE",
              "a compensating action is available but no compensation port is configured",
          )
        }
        when (val result = port.compensate(CompensateRequest(change, reason))) {
          is PortResult.Success -> null
          is PortResult.Failure -> result.error
        }
      }

  private fun portFailure(failure: PortError, stage: String) =
      failure.copy(message = "$stage failed: ${failure.message}")

  private fun failure(error: PortError, target: String? = error.target): WorkflowResult.Failure =
      WorkflowResult.Failure(failureData(error, target))

  private fun failureData(error: PortError, target: String? = error.target) =
      io.springkit.workflow.domain.FailureData(
          code = mapFailureCode(error.code),
          message = error.message,
          blockedBy =
              if (target == null) emptyList()
              else listOf(BlockedBy(error.code, error.message, target)),
          next =
              if (error.retryable)
                  listOf(NextAction(io.springkit.workflow.domain.ActorKind.AGENT, "retry"))
              else emptyList(),
      )

  private fun invalid(message: String, target: String? = null) =
      io.springkit.workflow.domain.FailureData(
          FailureCode.INVALID_ARGUMENT,
          message,
          blockedBy = listOfNotNull(target?.let { BlockedBy("INVALID_ARGUMENT", message, it) }),
      )

  private fun stateConflict(message: String) =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(FailureCode.STATE_CONFLICT, message),
      )

  private fun stale(message: String, target: String? = null) =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              FailureCode.STALE_REVISION,
              message,
              blockedBy = listOfNotNull(target?.let { BlockedBy("STALE_REVISION", message, it) }),
          ),
      )

  private fun mapFailureCode(code: String): FailureCode =
      when (code.uppercase()) {
        "STALE_REVISION",
        "REVISION_CONFLICT",
        "CONCURRENT_MODIFICATION" -> FailureCode.STALE_REVISION
        "WORKTREE_REQUIRED",
        "NOT_MANAGED_WORKTREE" -> FailureCode.WORKTREE_REQUIRED
        "VALIDATION_REQUIRED",
        "CHECK_FAILED" -> FailureCode.VALIDATION_REQUIRED
        "DEPENDENCY_CYCLE" -> FailureCode.DEPENDENCY_CYCLE
        "INVALID_ARGUMENT" -> FailureCode.INVALID_ARGUMENT
        else -> FailureCode.STATE_CONFLICT
      }

  private fun idempotencyConflict(message: String) =
      io.springkit.workflow.domain.FailureData(
          FailureCode.IDEMPOTENCY_CONFLICT,
          message,
          blockedBy = listOf(BlockedBy("IDEMPOTENCY_CONFLICT", message)),
      )

  private data class Base(val branch: String, val revision: String)
}

/**
 * Application service for wf-03. ValidationPort executes commands; this service supplies the
 * repository-defined read-only checks and accepts a result only for the content it inspected.
 */
class CheckUseCase(
    private val workspacePort: WorkspacePort,
    private val gitPort: GitPort,
    private val storePort: WorkflowStorePort,
    private val validationPort: ValidationPort,
    private val taskPort: TaskPort? = null,
) {
  fun check(request: CheckRequest): WorkflowResult<CheckResponse> = execute(request)

  fun handle(request: CheckRequest): WorkflowResult<CheckResponse> = execute(request)

  fun execute(request: CheckRequest): WorkflowResult<CheckResponse> {
    if (request.workspaceId == null && request.subTaskId == null && request.workspacePath == null) {
      return WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              FailureCode.WORKTREE_REQUIRED,
              "check requires a managed workspace target",
          ),
      )
    }
    val workspaceResult =
        workspacePort.get(
            WorkspaceLookupRequest(
                workspaceId = request.workspaceId,
                subTaskId = request.subTaskId,
                path = request.workspacePath,
            ),
        )
    val workspace =
        when (workspaceResult) {
          is PortResult.Success -> workspaceResult.value.workspace
          is PortResult.Failure -> return failure(workspaceResult.error)
        }
    if (!workspace.managed) {
      return WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              FailureCode.WORKTREE_REQUIRED,
              "check requires a managed workspace",
              blockedBy =
                  listOf(BlockedBy("WORKTREE_REQUIRED", "workspace is not managed", workspace.id)),
          ),
      )
    }
    if (request.subTaskId != null && request.subTaskId != workspace.subTaskId) {
      return stale("workspace is not connected to the requested subtask", workspace.id)
    }

    val snapshotResult =
        storePort.snapshot(
            StoreSnapshotRequest(scope = StoreScope.ALL, subTaskId = workspace.subTaskId),
        )
    val snapshot =
        when (snapshotResult) {
          is PortResult.Success -> snapshotResult.value.snapshot
          is PortResult.Failure -> return failure(snapshotResult.error)
        }
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return stale("workflow store revision is stale", snapshot.revision)
    }
    val subTask = snapshot.subTasks.firstOrNull { it.id == workspace.subTaskId }
    if (subTask == null) {
      return stateConflict("workspace subtask is not present in the workflow store")
    }
    if (subTask.branch != workspace.branch) {
      return stateConflict("workspace branch does not match the subtask branch")
    }
    if (subTask.requires != null && snapshot.subTasks.none { it.id == subTask.requires }) {
      return stateConflict("subtask dependency is not present in the workflow store")
    }
    if (taskPort != null && subTask.requires != null) {
      when (val result = taskPort.getSubTask(SubTaskLookupRequest(subTask.requires))) {
        is PortResult.Success -> Unit
        is PortResult.Failure -> return failure(result.error, subTask.requires)
      }
    }

    val initialStatus =
        when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
          is PortResult.Success -> result.value.status
          is PortResult.Failure -> return failure(result.error, workspace.id)
        }
    if (initialStatus.conflicts.isNotEmpty()) {
      return stateConflict("worktree contains unresolved conflicts")
    }
    val required =
        listOf(
            Validation("test", "test", ValidationStatus.PENDING, revision = initialStatus.revision),
            Validation(
                "build",
                "build",
                ValidationStatus.PENDING,
                revision = initialStatus.revision,
            ),
        )
    val run =
        when (
            val result =
                validationPort.run(
                    RunValidationRequest(
                        workspaceId = workspace.id,
                        revision = initialStatus.revision,
                        fingerprint = initialStatus.fingerprint,
                        required = required,
                    ),
                )
        ) {
          is PortResult.Success -> result.value
          is PortResult.Failure -> return failure(result.error, workspace.id)
        }
    val summary = run.summary ?: summaryFrom(run.validations, initialStatus)
    if (!summary.appliesTo(initialStatus.fingerprint, initialStatus.revision)) {
      return stale("validation result does not apply to the inspected content", workspace.id)
    }
    val finalStatus =
        when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
          is PortResult.Success -> result.value.status
          is PortResult.Failure -> return failure(result.error, workspace.id)
        }
    if (
        finalStatus.revision != initialStatus.revision ||
            finalStatus.fingerprint != initialStatus.fingerprint
    ) {
      return stale("worktree changed while validation was running", workspace.id)
    }
    val publishable =
        summary.passed && summary.appliesTo(finalStatus.fingerprint, finalStatus.revision)
    val transaction =
        StoreTransactionRequest(
            transactionId = "tx-check-${workspace.id}-${finalStatus.fingerprint}",
            expectedRevision = snapshot.revision,
            idempotencyKey = "check:${workspace.id}:${finalStatus.fingerprint}",
        )
    when (val result = storePort.begin(transaction)) {
      is PortResult.Failure -> return failure(result.error)
      is PortResult.Success ->
          if (result.value.state != StoreTransactionState.OPEN) {
            return stateConflict("check transaction is not open")
          }
    }
    val storedSnapshot = snapshot.copy(checks = snapshot.checks + (subTask.id to summary))
    when (
        val result =
            storePort.write(
                StoreWriteRequest(transaction.transactionId, snapshot.revision, storedSnapshot)
            )
    ) {
      is PortResult.Failure -> {
        storePort.rollback(transaction)
        return failure(result.error)
      }
      is PortResult.Success -> Unit
    }
    when (val result = storePort.commit(transaction)) {
      is PortResult.Failure -> return failure(result.error)
      is PortResult.Success -> Unit
    }
    return WorkflowResult.Success(
        CheckResponse(
            workspace = workspace,
            revision = finalStatus.revision,
            fingerprint = finalStatus.fingerprint,
            validations = run.validations,
            summary = summary,
            publishable = publishable,
        ),
        next =
            if (publishable)
                listOf(NextAction(io.springkit.workflow.domain.ActorKind.AGENT, "open_review"))
            else listOf(NextAction(io.springkit.workflow.domain.ActorKind.AGENT, "fix_validation")),
    )
  }

  private fun summaryFrom(validations: List<Validation>, status: GitStatus): CheckSummary {
    val checks = validations.map {
      CheckResult(
          id = it.id,
          name = it.name,
          status = it.status,
          fingerprint = status.fingerprint,
          revision = status.revision,
          message = it.message,
      )
    }
    return CheckSummary(status.fingerprint, status.revision, checks)
  }

  private fun failure(error: PortError, target: String? = error.target): WorkflowResult.Failure =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              code =
                  when (error.code.uppercase()) {
                    "STALE_REVISION",
                    "REVISION_CONFLICT" -> FailureCode.STALE_REVISION
                    "WORKTREE_REQUIRED",
                    "NOT_MANAGED_WORKTREE" -> FailureCode.WORKTREE_REQUIRED
                    "VALIDATION_REQUIRED",
                    "CHECK_FAILED" -> FailureCode.VALIDATION_REQUIRED
                    else -> FailureCode.STATE_CONFLICT
                  },
              message = error.message,
              blockedBy =
                  if (target == null) emptyList()
                  else listOf(BlockedBy(error.code, error.message, target)),
              next =
                  if (error.retryable)
                      listOf(NextAction(io.springkit.workflow.domain.ActorKind.AGENT, "retry"))
                  else emptyList(),
          ),
      )

  private fun stateConflict(message: String) =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(FailureCode.STATE_CONFLICT, message),
      )

  private fun stale(message: String, target: String? = null) =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              FailureCode.STALE_REVISION,
              message,
              blockedBy = listOfNotNull(target?.let { BlockedBy("STALE_REVISION", message, it) }),
          ),
      )
}

/** Facade used by inbound adapters that expose both commands from one application boundary. */
class StartCheckUseCases(
    taskPort: TaskPort,
    gitPort: GitPort,
    workspacePort: WorkspacePort,
    storePort: WorkflowStorePort,
    validationPort: ValidationPort,
    idPort: IdPort? = null,
    compensationPort: CompensationPort? = null,
    clockPort: ClockPort? = null,
) {
  private val startUseCase =
      StartUseCase(taskPort, gitPort, workspacePort, storePort, idPort, compensationPort, clockPort)
  private val checkUseCase =
      CheckUseCase(workspacePort, gitPort, storePort, validationPort, taskPort)

  fun execute(request: StartRequest): WorkflowResult<StartResponse> = startUseCase.execute(request)

  fun execute(request: CheckRequest): WorkflowResult<CheckResponse> = checkUseCase.execute(request)

  fun start(request: StartRequest): WorkflowResult<StartResponse> = startUseCase.execute(request)

  fun check(request: CheckRequest): WorkflowResult<CheckResponse> = checkUseCase.execute(request)
}

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> =
    filterNot { id(it) == id(value) } + value

private fun TaskPort.createSubTaskFailure(request: StartRequest): PortError =
    PortError("TASK_CREATE_FAILED", "could not create subtask for ${request.taskId.value}")

private fun GitPort.createBranchFailure(subTask: SubTask, base: Any): PortError =
    PortError("BRANCH_CREATE_FAILED", "could not create branch ${subTask.branch}")

private fun GitPort.createWorktreeFailure(workspaceId: WorkspaceId): PortError =
    PortError("WORKTREE_CREATE_FAILED", "could not create worktree $workspaceId")

private fun WorkspacePort.createFailure(workspaceId: WorkspaceId): PortError =
    PortError("WORKSPACE_CREATE_FAILED", "could not record workspace $workspaceId")
