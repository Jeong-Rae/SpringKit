package io.springkit.workflow.adapter.local

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.Compensation
import io.springkit.workflow.application.CreateSubTaskRequest
import io.springkit.workflow.application.CreateSubTaskResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.SubTaskLookupRequest
import io.springkit.workflow.application.SubTaskLookupResponse
import io.springkit.workflow.application.TaskLookupRequest
import io.springkit.workflow.application.TaskLookupResponse
import io.springkit.workflow.application.TaskPort
import io.springkit.workflow.application.UpdateExternalSubTaskRequest
import io.springkit.workflow.application.UpdateExternalSubTaskResponse
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task

/*
 * Workflow Store snapshot을 외부 Task 연결의 로컬 읽기 모델로 사용하는 [TaskPort] 구현입니다. 외부 Task 제공자를 설정하지 않은 로컬
 * 실행에서는 외부 ID를 검증하지 않고 임시 Task로 구체화하며, 실제 Task와 SubTask 상태는 애플리케이션 트랜잭션이 저장합니다.
 */
class SnapshotTaskAdapter(
    private val snapshotProvider: () -> WorkflowStoreSnapshot,
) : TaskPort {
  /*
   * 고정 snapshot을 사용하는 테스트용 Task adapter를 만듭니다.
   */
  constructor(snapshot: WorkflowStoreSnapshot) : this({ snapshot })

  override fun get(request: TaskLookupRequest): PortResult<TaskLookupResponse> {
    if (request.taskId == null && request.externalId == null) {
      return failure("TASK_LOOKUP_INVALID", "Task ID 또는 외부 Task ID가 필요합니다.")
    }
    val snapshot = readSnapshot() ?: return lastFailure
    val task =
        snapshot.tasks.firstOrNull {
          (request.taskId == null || request.taskId == it.id) &&
              (request.externalId == null || request.externalId == it.externalId)
        }
            ?: request.externalId
                ?.takeIf { request.taskId == null || request.taskId == it.value }
                ?.let(::materializeTask)
            ?: return failure(
                "TASK_NOT_FOUND",
                "Task를 찾을 수 없습니다.",
                request.externalId?.value ?: request.taskId,
            )
    return PortResult.Success(TaskLookupResponse(task))
  }

  override fun getSubTask(request: SubTaskLookupRequest): PortResult<SubTaskLookupResponse> {
    val snapshot = readSnapshot() ?: return lastFailure
    val subTask = snapshot.subTasks.firstOrNull { it.id == request.subTaskId }
    return if (subTask == null) {
      failure("SUBTASK_NOT_FOUND", "SubTask를 찾을 수 없습니다.", request.subTaskId)
    } else {
      PortResult.Success(SubTaskLookupResponse(subTask))
    }
  }

  override fun createSubTask(request: CreateSubTaskRequest): PortResult<CreateSubTaskResponse> {
    val snapshot = readSnapshot() ?: return lastFailure
    val task =
        snapshot.tasks.firstOrNull { it.externalId == request.externalTaskId }
            ?: materializeTask(request.externalTaskId)
    val existing =
        snapshot.subTasks.firstOrNull {
          it.id == request.subTaskId
        }
    val subTask =
        existing
            ?: SubTask(
                id = request.subTaskId,
                taskId = task.id,
                title = request.title,
                state = SubTaskState.DEVELOPMENT,
                requires = request.requires,
            )
    if (
        existing != null &&
            (existing.taskId != task.id ||
                existing.title != request.title ||
                existing.requires != request.requires)
    ) {
      return failure(
          "SUBTASK_ID_CONFLICT",
          "이미 다른 메타데이터로 사용 중인 SubTask ID입니다.",
          request.subTaskId,
      )
    }
    val storedTask =
        if (subTask.id in task.subTaskIds) task
        else task.copy(subTaskIds = task.subTaskIds + subTask.id)
    return PortResult.Success(
        CreateSubTaskResponse(
            task = storedTask,
            subTask = subTask,
            change =
                ChangeReceipt(
                    id = "task-create-${request.requestId}",
                    operation = "create-subtask",
                    compensation =
                        Compensation(
                            id = "task-delete-${request.requestId}",
                            operation = "delete-subtask",
                            idempotencyKey = request.requestId,
                        ),
                ),
        )
    )
  }

  override fun updateSubTask(
      request: UpdateExternalSubTaskRequest
  ): PortResult<UpdateExternalSubTaskResponse> {
    val snapshot = readSnapshot() ?: return lastFailure
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == request.subTaskId }
            ?: return failure("SUBTASK_NOT_FOUND", "SubTask를 찾을 수 없습니다.", request.subTaskId)
    val task =
        snapshot.tasks.firstOrNull { it.id == subTask.taskId }
            ?: return failure("TASK_NOT_FOUND", "SubTask의 Task를 찾을 수 없습니다.", subTask.taskId)
    if (task.externalId != request.externalTaskId) {
      return failure("TASK_MISMATCH", "SubTask가 요청한 외부 Task에 속하지 않습니다.", request.subTaskId)
    }
    return PortResult.Success(
        UpdateExternalSubTaskResponse(
            externalTaskId = request.externalTaskId,
            subTaskId = request.subTaskId,
            state = request.state,
            change = ChangeReceipt("task-update-${request.requestId}", "update-subtask"),
        )
    )
  }

  private var lastFailure: PortResult.Failure =
      PortResult.Failure(
          PortError("TASK_SNAPSHOT_UNAVAILABLE", "Workflow Store snapshot을 읽을 수 없습니다.")
      )

  private fun readSnapshot(): WorkflowStoreSnapshot? =
      try {
        snapshotProvider()
      } catch (failure: Exception) {
        lastFailure =
            PortResult.Failure(
                PortError(
                    code = "TASK_SNAPSHOT_UNAVAILABLE",
                    message = "Workflow Store snapshot을 읽을 수 없습니다: ${failure.message}",
                    retryable = true,
                )
            )
        null
      }

  private fun materializeTask(externalTaskId: ExternalTaskId): Task =
      Task(
          id = externalTaskId.value,
          externalId = externalTaskId,
          title = externalTaskId.value,
      )

  private fun <T> failure(
      code: String,
      message: String,
      target: String? = null,
  ): PortResult<T> = PortResult.Failure(PortError(code, message, target = target))
}

/*
 * 외부 Task 연결 adapter의 의도를 드러내는 호환 별칭입니다.
 */
typealias LocalTaskAdapter = SnapshotTaskAdapter
