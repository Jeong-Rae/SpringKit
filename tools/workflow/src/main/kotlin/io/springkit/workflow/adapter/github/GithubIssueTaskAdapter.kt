package io.springkit.workflow.adapter.github

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
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskState
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * `gh issue` 응답을 Workflow 외부 Task 모델로 변환하는 최소 응답 모델입니다.
 */
@Serializable
data class GithubIssue(
    val number: Long = 0,
    val title: String = "",
    val state: String = "",
    val stateReason: String? = null,
    val body: String = "",
    val url: String? = null,
)

/*
 * 설치된 `gh` CLI로 GitHub Issue를 외부 Task와 SubTask로 연결하는 [TaskPort]입니다.
 *
 * 외부 Task ID는 GitHub Issue 번호 또는 URL이어야 합니다. SubTask ID와 부모 관계는 Issue 본문의 Workflow metadata marker로
 * 보존합니다.
 */
class GithubIssueTaskAdapter(
    private val repositoryRoot: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
    private val issueLimit: Int = 100,
) : TaskPort {
  init {
    require(issueLimit > 0) { "issue limit must be positive" }
  }

  override fun get(request: TaskLookupRequest): PortResult<TaskLookupResponse> {
    val reference = request.externalId?.value ?: request.taskId
    if (reference.isNullOrBlank()) {
      return failure("TASK_LOOKUP_INVALID", "Task ID 또는 외부 Task ID가 필요합니다.")
    }
    val issue = viewIssue(reference) ?: return lastFailure
    val task = issue.toTask(reference)
    if (
        request.taskId != null &&
            request.taskId != task.id &&
            request.taskId != task.externalId.value
    ) {
      return failure("TASK_NOT_FOUND", "GitHub Issue가 요청한 Task ID와 일치하지 않습니다.", reference)
    }
    return PortResult.Success(TaskLookupResponse(task))
  }

  override fun getSubTask(request: SubTaskLookupRequest): PortResult<SubTaskLookupResponse> {
    val issue = findSubTask(request.subTaskId) ?: return lastFailure
    val subTask =
        issue.toSubTask(request.subTaskId)
            ?: return failure(
                "GITHUB_TASK_RESPONSE_INVALID",
                "GitHub Issue의 SubTask metadata가 올바르지 않습니다.",
                request.subTaskId,
            )
    return PortResult.Success(SubTaskLookupResponse(subTask))
  }

  override fun createSubTask(request: CreateSubTaskRequest): PortResult<CreateSubTaskResponse> {
    val taskResult = get(TaskLookupRequest(externalId = request.externalTaskId))
    val task =
        when (taskResult) {
          is PortResult.Failure -> return taskResult
          is PortResult.Success -> taskResult.value.task
        }
    val existing = findSubTask(request.subTaskId)
    if (existing == null && lastFailure.error.code != "SUBTASK_NOT_FOUND") {
      return lastFailure
    }
    if (existing != null) {
      val subTask =
          existing.toSubTask(request.subTaskId)
              ?: return failure(
                  "GITHUB_TASK_RESPONSE_INVALID",
                  "GitHub Issue의 SubTask metadata가 올바르지 않습니다.",
                  request.subTaskId,
              )
      if (
          subTask.taskId != task.id ||
              subTask.title != request.title ||
              subTask.requires != request.requires
      ) {
        return failure(
            "SUBTASK_ID_CONFLICT",
            "이미 다른 메타데이터로 사용 중인 SubTask ID입니다.",
            request.subTaskId,
        )
      }
      return PortResult.Success(
          CreateSubTaskResponse(
              task = task.withSubTask(subTask.id),
              subTask = subTask,
              change = existingReceipt(request),
          )
      )
    }

    val body = request.metadataBody(task)
    val created =
        runGh(
            listOf("gh", "issue", "create", "--title", request.title, "--body", body),
            request.externalTaskId.value,
        ) ?: return lastFailure
    val reference = created.stdout.trim().lineSequence().lastOrNull()?.trim()
    if (reference.isNullOrBlank()) {
      return failure(
          "GITHUB_TASK_RESPONSE_INVALID",
          "GitHub Issue 생성 결과에서 Issue 참조를 찾을 수 없습니다.",
          request.subTaskId,
      )
    }
    val issue = viewIssue(reference) ?: return lastFailure
    val subTask =
        issue.toSubTask(request.subTaskId)
            ?: return failure(
                "GITHUB_TASK_RESPONSE_INVALID",
                "GitHub Issue 생성 결과의 SubTask metadata가 올바르지 않습니다.",
                request.subTaskId,
            )
    if (
        subTask.taskId != task.id ||
            subTask.title != request.title ||
            subTask.requires != request.requires
    ) {
      return failure(
          "GITHUB_TASK_RESPONSE_INVALID",
          "GitHub Issue 생성 결과의 SubTask metadata가 요청과 다릅니다.",
          request.subTaskId,
      )
    }
    return PortResult.Success(
        CreateSubTaskResponse(
            task = task.withSubTask(subTask.id),
            subTask = subTask,
            change =
                ChangeReceipt(
                    id = "github-task-create-${request.requestId}",
                    operation = "github-task-create-subtask",
                    compensation =
                        Compensation(
                            id = "github-task-close-${request.requestId}",
                            operation = "github-task-close-subtask",
                            idempotencyKey = request.requestId,
                        ),
                ),
        )
    )
  }

  override fun updateSubTask(
      request: UpdateExternalSubTaskRequest
  ): PortResult<UpdateExternalSubTaskResponse> {
    val issue = findSubTask(request.subTaskId) ?: return lastFailure
    val subTask =
        issue.toSubTask(request.subTaskId)
            ?: return failure(
                "GITHUB_TASK_RESPONSE_INVALID",
                "GitHub Issue의 SubTask metadata가 올바르지 않습니다.",
                request.subTaskId,
            )
    val expectedTaskId =
        issue.parentTaskId()
            ?: return failure(
                "GITHUB_TASK_RESPONSE_INVALID",
                "GitHub Issue에 부모 Task metadata가 없습니다.",
                request.subTaskId,
            )
    if (issue.parentExternalTaskId() != request.externalTaskId.value) {
      return failure("TASK_MISMATCH", "SubTask가 요청한 외부 Task에 속하지 않습니다.", request.subTaskId)
    }
    val task = get(TaskLookupRequest(externalId = request.externalTaskId))
    if (task is PortResult.Failure) return task
    val taskId = (task as PortResult.Success).value.task.id
    if (expectedTaskId != taskId || subTask.taskId != taskId) {
      return failure("TASK_MISMATCH", "SubTask가 요청한 외부 Task에 속하지 않습니다.", request.subTaskId)
    }
    val updatedBody = issue.replaceState(request.state)
    runGh(
        listOf("gh", "issue", "edit", issue.number.toString(), "--body", updatedBody),
        request.subTaskId,
    ) ?: return lastFailure
    val stateCommand = request.state.issueStateCommand(issue)
    if (stateCommand != null && runGh(stateCommand, request.subTaskId) == null) {
      return lastFailure
    }
    return PortResult.Success(
        UpdateExternalSubTaskResponse(
            externalTaskId = request.externalTaskId,
            subTaskId = request.subTaskId,
            state = request.state,
            change =
                ChangeReceipt(
                    id = "github-task-update-${request.requestId}",
                    operation = "github-task-update-subtask",
                ),
        )
    )
  }

  private fun viewIssue(reference: String): GithubIssue? {
    val result =
        runGh(
            listOf("gh", "issue", "view", reference, "--json", ISSUE_FIELDS),
            reference,
        ) ?: return null
    return decode(result.stdout, reference)
  }

  private fun findSubTask(subTaskId: String): GithubIssue? {
    val result =
        runGh(
            listOf(
                "gh",
                "issue",
                "list",
                "--state",
                "all",
                "--limit",
                issueLimit.toString(),
                "--json",
                ISSUE_FIELDS,
            ),
            subTaskId,
        ) ?: return null
    val issues =
        try {
          json.decodeFromString<List<GithubIssue>>(result.stdout)
        } catch (_: SerializationException) {
          lastFailure =
              PortResult.Failure(
                  PortError(
                      "GITHUB_TASK_RESPONSE_INVALID",
                      "GitHub Issue 목록 응답 JSON을 해석할 수 없습니다.",
                      target = subTaskId,
                  )
              )
          return null
        }
    val issue = issues.firstOrNull { it.subTaskId() == subTaskId }
    if (issue == null) {
      lastFailure =
          PortResult.Failure(
              PortError(
                  "SUBTASK_NOT_FOUND",
                  "GitHub Issue에서 SubTask를 찾을 수 없습니다.",
                  target = subTaskId,
              )
          )
    }
    return issue
  }

  private fun decode(output: String, target: String): GithubIssue? =
      try {
        json.decodeFromString<GithubIssue>(output)
      } catch (_: SerializationException) {
        lastFailure =
            PortResult.Failure(
                PortError(
                    "GITHUB_TASK_RESPONSE_INVALID",
                    "GitHub Issue 응답 JSON을 해석할 수 없습니다.",
                    target = target,
                )
            )
        null
      }

  private fun runGh(command: List<String>, target: String): CommandResult? {
    val result =
        try {
          commandRunner.run(command, repositoryRoot)
        } catch (failure: Exception) {
          lastFailure =
              PortResult.Failure(
                  PortError(
                      "GITHUB_TASK_COMMAND_FAILED",
                      "gh 명령을 실행할 수 없습니다: ${failure.message ?: failure::class.simpleName}",
                      retryable = true,
                      target = target,
                  )
              )
          return null
        }
    if (result.exitCode != 0) {
      lastFailure =
          PortResult.Failure(
              PortError(
                  "GITHUB_TASK_COMMAND_FAILED",
                  result.stderr
                      .trim()
                      .ifBlank { result.stdout.trim() }
                      .ifBlank {
                        "gh 명령이 종료 코드 ${result.exitCode}로 실패했습니다."
                      },
                  retryable = result.exitCode == 2,
                  target = target,
              )
          )
      return null
    }
    return result
  }

  private var lastFailure: PortResult.Failure =
      PortResult.Failure(PortError("GITHUB_TASK_COMMAND_FAILED", "gh 명령을 실행할 수 없습니다."))

  private fun <T> failure(code: String, message: String, target: String? = null): PortResult<T> {
    val result = PortResult.Failure(PortError(code, message, target = target))
    lastFailure = result
    return result
  }

  private companion object {
    const val ISSUE_FIELDS = "number,title,state,stateReason,body,url"
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}

private fun GithubIssue.toTask(reference: String): Task {
  val id = number.takeIf { it > 0 }?.toString() ?: reference
  return Task(
      id = id,
      externalId = ExternalTaskId(reference),
      title = title.ifBlank { reference },
      state =
          when {
            state.equals("CLOSED", ignoreCase = true) &&
                stateReason.equals("NOT_PLANNED", ignoreCase = true) -> TaskState.CANCELLED
            state.equals("CLOSED", ignoreCase = true) -> TaskState.COMPLETED
            else -> TaskState.OPEN
          },
  )
}

private fun GithubIssue.toSubTask(requestedId: String): SubTask? {
  val parentId = parentTaskId() ?: return null
  val subTaskId = subTaskId()
  if (subTaskId != requestedId) {
    return null
  }
  val titleValue = title.takeIf(String::isNotBlank) ?: return null
  return SubTask(
      id = requestedId,
      taskId = parentId,
      title = titleValue,
      state = subTaskState(),
      requires = marker("requires"),
  )
}

private fun GithubIssue.subTaskId(): String? = marker("subtask-id")

private fun GithubIssue.parentTaskId(): String? = marker("parent-task")

private fun GithubIssue.parentExternalTaskId(): String? = marker("parent-external-task")

private fun GithubIssue.subTaskState(): SubTaskState =
    marker("subtask-state")?.let { value ->
      runCatching { SubTaskState.valueOf(value) }.getOrNull()
    }
        ?: if (state.equals("CLOSED", ignoreCase = true)) SubTaskState.MERGED
        else SubTaskState.DEVELOPMENT

private fun GithubIssue.marker(name: String): String? =
    body
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("<!-- workflow-$name:") && it.endsWith("-->") }
        ?.removePrefix("<!-- workflow-$name:")
        ?.removeSuffix("-->")
        ?.trim()
        ?.takeIf(String::isNotBlank)

private fun CreateSubTaskRequest.metadataBody(task: Task): String =
    buildList {
          add("<!-- workflow-parent-task: ${task.id} -->")
          add("<!-- workflow-parent-external-task: ${externalTaskId.value} -->")
          add("<!-- workflow-subtask-id: $subTaskId -->")
          add("<!-- workflow-subtask-state: ${SubTaskState.DEVELOPMENT.name} -->")
          requires?.let { add("<!-- workflow-requires: $it -->") }
        }
        .joinToString("\n")

private fun GithubIssue.replaceState(state: SubTaskState): String {
  val marker = "<!-- workflow-subtask-state:"
  val replacement = "<!-- workflow-subtask-state: ${state.name} -->"
  val lines = body.lineSequence().toMutableList()
  val index = lines.indexOfFirst { it.trim().startsWith(marker) }
  if (index >= 0) lines[index] = replacement else lines.add(replacement)
  return lines.joinToString("\n")
}

private fun SubTaskState.issueStateCommand(issue: GithubIssue): List<String>? =
    when {
      this == SubTaskState.MERGED && !issue.state.equals("CLOSED", ignoreCase = true) ->
          listOf("gh", "issue", "close", issue.number.toString())
      this != SubTaskState.MERGED && issue.state.equals("CLOSED", ignoreCase = true) ->
          listOf("gh", "issue", "reopen", issue.number.toString())
      else -> null
    }

private fun Task.withSubTask(subTaskId: String): Task =
    if (subTaskId in subTaskIds) this else copy(subTaskIds = subTaskIds + subTaskId)

private fun GithubIssueTaskAdapter.existingReceipt(request: CreateSubTaskRequest): ChangeReceipt =
    ChangeReceipt(
        id = "github-task-existing-${request.requestId}",
        operation = "github-task-create-subtask",
    )
