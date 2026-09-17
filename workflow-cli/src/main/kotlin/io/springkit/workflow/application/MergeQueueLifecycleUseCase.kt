package io.springkit.workflow.application

import io.springkit.workflow.domain.ChangeRevisionId
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.MergeRecorded
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.WorkflowResult

/** Merge Queue 검증을 통과한 SubTask를 squash merge하고 통합 상태를 저장하는 요청입니다. */
data class MergeQueueLifecycleRequest(
    val subTaskId: SubTaskId,
    val mergeQueueEntryId: String? = null,
    val changeRevisionId: ChangeRevisionId? = null,
    val requestId: String = "merge-$subTaskId",
    val expectedStoreRevision: String? = null,
) {
  init {
    require(subTaskId.isNotBlank()) { "merge subtask id must not be blank" }
    require(mergeQueueEntryId == null || mergeQueueEntryId.isNotBlank()) {
      "merge queue entry id must not be blank"
    }
    require(changeRevisionId == null || changeRevisionId.isNotBlank()) {
      "merge change revision id must not be blank"
    }
    require(requestId.isNotBlank()) { "merge request id must not be blank" }
  }
}

/** squash merge 결과와 외부 Task 동기화 결과를 반환합니다. */
data class MergeQueueLifecycleResponse(
    val task: Task,
    val subTask: SubTask,
    val pullRequest: PullRequest,
    val integration: Integration,
    val mergeQueue: MergeQueueEntry,
)

/** Merge Queue 통합과 Workflow Store 상태 전이를 조정합니다. */
class MergeQueueLifecycleUseCase(
    private val storePort: WorkflowStorePort,
    private val mergeQueuePort: MergeQueuePort,
    private val taskPort: TaskPort? = null,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
) {
  /** 기존 Application 호출부의 Store, Task, Merge Queue 포트 순서를 지원합니다. */
  constructor(
      orderedStorePort: WorkflowStorePort,
      orderedTaskPort: TaskPort,
      orderedMergeQueuePort: MergeQueuePort,
      orderedIdPort: IdPort? = null,
      orderedClockPort: ClockPort? = null,
      orderedCompensationPort: CompensationPort? = null,
  ) : this(
      orderedStorePort,
      orderedMergeQueuePort,
      orderedTaskPort,
      orderedIdPort,
      orderedClockPort,
      orderedCompensationPort,
  )

  /** Merge Queue 검증 결과를 확인하고 squash merge를 실행합니다. */
  fun merge(request: MergeQueueLifecycleRequest): WorkflowResult<MergeQueueLifecycleResponse> =
      execute(request)

  /** Merge Queue 통합 요청을 애플리케이션 명령 형태로 실행합니다. */
  fun execute(request: MergeQueueLifecycleRequest): WorkflowResult<MergeQueueLifecycleResponse> {
    val snapshot =
        when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          is PortResult.Success -> result.value.snapshot
        }
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.subTaskId,
      )
    }
    idempotentResponse(snapshot, request)?.let {
      return WorkflowResult.Success(it)
    }

    val transactionRequest =
        StoreTransactionRequest(
            transactionId = issueTransactionId(request.requestId),
            expectedRevision = snapshot.revision,
            idempotencyKey = request.requestId,
        )
    return WorkflowTransaction(storePort, compensationGateway()).execute(transactionRequest) {
        current ->
      mergeIn(current, request)
    }
  }

  /** 통합 완료 이벤트를 처리하는 공통 진입점입니다. */
  fun handle(request: MergeQueueLifecycleRequest): WorkflowResult<MergeQueueLifecycleResponse> =
      execute(request)

  private fun mergeIn(
      snapshot: WorkflowStoreSnapshot,
      request: MergeQueueLifecycleRequest,
  ): PortResult<TransactionMutation<MergeQueueLifecycleResponse>> {
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == request.subTaskId }
            ?: return failurePort(
                FailureCode.SUBTASK_NOT_FOUND,
                "subtask was not found",
                request.subTaskId,
            )
    val task =
        snapshot.tasks.firstOrNull { it.id == subTask.taskId }
            ?: return failurePort(FailureCode.TASK_NOT_FOUND, "task was not found", subTask.taskId)
    val pullRequestId =
        subTask.pullRequestId
            ?: return failurePort(
                FailureCode.REVIEW_NOT_FOUND,
                "subtask does not have a pull request",
                subTask.id,
            )
    val pullRequest =
        snapshot.pullRequests.firstOrNull { it.id == pullRequestId }
            ?: return failurePort(
                FailureCode.REVIEW_NOT_FOUND,
                "pull request was not found",
                pullRequestId,
            )
    if (pullRequest.subTaskId != subTask.id) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "pull request does not belong to the subtask",
          pullRequest.id,
      )
    }

    val queue =
        snapshot.mergeQueue.firstOrNull {
          if (request.mergeQueueEntryId != null) it.id == request.mergeQueueEntryId
          else it.subTaskId == subTask.id
        }
            ?: return failurePort(
                FailureCode.MERGE_QUEUE_FAILED,
                "merge queue entry was not found",
                subTask.id,
            )
    if (queue.subTaskId != subTask.id || queue.pullRequestId != pullRequest.id) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "merge queue entry does not belong to the subtask pull request",
          queue.id,
      )
    }
    val changeRevisionId = request.changeRevisionId ?: pullRequest.changeRevision.id
    if (
        queue.changeRevisionId != changeRevisionId ||
            pullRequest.changeRevision.id != changeRevisionId
    ) {
      return failurePort(FailureCode.STALE_REVISION, "change revision is stale", subTask.id)
    }
    if (subTask.state == SubTaskState.MERGED && pullRequest.state == PullRequestState.MERGED) {
      val integration = snapshot.integrations.firstOrNull { it.subTaskId == subTask.id }
      if (integration?.state == IntegrationState.MERGED && integration.mainRevision != null) {
        return PortResult.Success(
            TransactionMutation(
                MergeQueueLifecycleResponse(task, subTask, pullRequest, integration, queue),
                snapshot,
            )
        )
      }
    }
    if (queue.state != MergeQueueState.PASSED) {
      return failurePort(
          FailureCode.MERGE_QUEUE_FAILED,
          "merge queue validation has not passed",
          subTask.id,
      )
    }

    val providerQueue =
        when (
            val result = mergeQueuePort.get(GetMergeQueueRequest(pullRequestId = pullRequest.id))
        ) {
          is PortResult.Failure -> return result
          is PortResult.Success ->
              result.value.entries.singleOrNull { it.subTaskId == subTask.id }
                  ?: return failurePort(
                      FailureCode.MERGE_QUEUE_FAILED,
                      "merge queue provider did not return the requested entry",
                      queue.id,
                  )
        }
    if (
        providerQueue.changeRevisionId != changeRevisionId ||
            providerQueue.pullRequestId != pullRequest.id ||
            providerQueue.state != MergeQueueState.PASSED
    ) {
      return failurePort(
          FailureCode.STALE_REVISION,
          "merge queue provider state is stale",
          subTask.id,
      )
    }

    val merged =
        when (
            val result =
                mergeQueuePort.merge(
                    MergeQueueMergeRequest(
                        entryId = queue.id,
                        expectedChangeRevisionId = changeRevisionId,
                    )
                )
        ) {
          is PortResult.Failure -> return result
          is PortResult.Success -> result.value
        }
    val integration = merged.integration
    if (
        integration.subTaskId != subTask.id ||
            integration.state != IntegrationState.MERGED ||
            integration.mainRevision.isNullOrBlank()
    ) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "merge provider did not return a completed integration",
          subTask.id,
          merged.change,
      )
    }
    val mergedQueue =
        integration.mergeQueue?.also { returned ->
          if (
              returned.id != queue.id ||
                  returned.subTaskId != subTask.id ||
                  returned.pullRequestId != pullRequest.id ||
                  returned.changeRevisionId != changeRevisionId ||
                  returned.state != MergeQueueState.MERGED
          ) {
            return failurePort(
                FailureCode.INVARIANT_VIOLATION,
                "merge provider returned an invalid merge queue entry",
                queue.id,
                merged.change,
            )
          }
        } ?: queue.copy(state = MergeQueueState.MERGED)

    val updatedSubTask = subTask.copy(state = SubTaskState.MERGED)
    val updatedPullRequest = pullRequest.copy(state = PullRequestState.MERGED)
    val updatedIntegration = integration.copy(mergeQueue = mergedQueue)
    val taskChange =
        when (val result = updateExternalTask(task, subTask, request)) {
          is PortResult.Failure -> return result.withChange(merged.change)
          is PortResult.Success -> result.value.change
        }
    val event = MergeRecorded(subTask.id, integration.mainRevision, now(request.requestId))
    val updatedSnapshot =
        snapshot.copy(
            subTasks = snapshot.subTasks.replaceById(updatedSubTask) { it.id },
            pullRequests = snapshot.pullRequests.replaceById(updatedPullRequest) { it.id },
            integrations = snapshot.integrations.replaceOrAdd(updatedIntegration) { it.subTaskId },
            mergeQueue = snapshot.mergeQueue.replaceById(mergedQueue) { it.id },
            eventLog = snapshot.eventLog.append(event),
        )
    return PortResult.Success(
        TransactionMutation(
            MergeQueueLifecycleResponse(
                task = task,
                subTask = updatedSubTask,
                pullRequest = updatedPullRequest,
                integration = updatedIntegration,
                mergeQueue = mergedQueue,
            ),
            updatedSnapshot,
            changes = listOf(merged.change, taskChange).filterNotNull(),
        )
    )
  }

  private fun updateExternalTask(
      task: Task,
      subTask: SubTask,
      request: MergeQueueLifecycleRequest,
  ): PortResult<UpdateExternalSubTaskResponse> {
    val port =
        taskPort
            ?: return PortResult.Success(
                UpdateExternalSubTaskResponse(
                    task.externalId,
                    subTask.id,
                    SubTaskState.MERGED,
                    ChangeReceipt(
                        "task-update-skipped-${request.requestId}",
                        "skip-task-update",
                    ),
                )
            )
    val result =
        port.updateSubTask(
            UpdateExternalSubTaskRequest(
                externalTaskId = task.externalId,
                subTaskId = subTask.id,
                state = SubTaskState.MERGED,
                requestId = request.requestId,
            )
        )
    if (
        result is PortResult.Success &&
            (result.value.externalTaskId != task.externalId ||
                result.value.subTaskId != subTask.id ||
                result.value.state != SubTaskState.MERGED)
    ) {
      return PortResult.Failure(
          PortError(
              FailureCode.INVARIANT_VIOLATION.name,
              "task provider returned an invalid subtask update",
              target = subTask.id,
          ),
          result.value.change,
      )
    }
    return result
  }

  private fun idempotentResponse(
      snapshot: WorkflowStoreSnapshot,
      request: MergeQueueLifecycleRequest,
  ): MergeQueueLifecycleResponse? {
    val subTask = snapshot.subTasks.firstOrNull { it.id == request.subTaskId } ?: return null
    if (subTask.state != SubTaskState.MERGED) return null
    val task = snapshot.tasks.firstOrNull { it.id == subTask.taskId } ?: return null
    val pullRequestId = subTask.pullRequestId ?: return null
    val pullRequest = snapshot.pullRequests.firstOrNull { it.id == pullRequestId } ?: return null
    if (pullRequest.state != PullRequestState.MERGED) return null
    val integration =
        snapshot.integrations.firstOrNull { it.subTaskId == subTask.id } ?: return null
    if (integration.state != IntegrationState.MERGED || integration.mainRevision.isNullOrBlank()) {
      return null
    }
    val queue =
        snapshot.mergeQueue.firstOrNull {
          (request.mergeQueueEntryId == null || it.id == request.mergeQueueEntryId) &&
              it.subTaskId == subTask.id
        } ?: return null
    return MergeQueueLifecycleResponse(
        task,
        subTask,
        pullRequest,
        integration,
        integration.mergeQueue ?: queue,
    )
  }

  private fun issueTransactionId(requestId: String): String =
      when (val result = idPort?.issue(IssueIdRequest(IdKind.TRANSACTION, requestId))) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value ?: "tx-$requestId"
        is PortResult.Failure,
        null -> "tx-$requestId"
      }

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null -> 0L
      }

  private fun compensationGateway(): CompensationPort =
      compensationPort
          ?: object : CompensationPort {
            override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
                PortResult.Success(CompensateResponse(request.change))
          }

  private fun failurePort(
      code: FailureCode,
      message: String,
      target: String,
      change: ChangeReceipt? = null,
  ): PortResult.Failure = PortResult.Failure(PortError(code.name, message, target = target), change)

  private fun failure(
      code: FailureCode,
      message: String,
      target: String,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          io.springkit.workflow.domain.FailureData(
              code,
              message,
              blockedBy =
                  listOf(io.springkit.workflow.domain.BlockedBy(code.name, message, target)),
          )
      )
}

private fun PortResult.Failure.withChange(change: ChangeReceipt): PortResult.Failure =
    PortResult.Failure(error, this.change ?: change)

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> = map { current ->
  if (id(current) == id(value)) value else current
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) replaceById(value, id) else this + value
