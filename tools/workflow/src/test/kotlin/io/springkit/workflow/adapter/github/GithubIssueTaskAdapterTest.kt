package io.springkit.workflow.adapter.github

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.CreateSubTaskRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.SubTaskLookupRequest
import io.springkit.workflow.application.TaskLookupRequest
import io.springkit.workflow.application.UpdateExternalSubTaskRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.SubTaskState
import java.nio.file.Path

class GithubIssueTaskAdapterTest :
    FunSpec({
      context("GitHub Issue를 외부 Task로 조회하면") {
        test("gh issue view 응답을 입력하면, Task 상태로 변환합니다") {
          val runner = IssueRecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {"number":42,"title":"추천 알고리즘 실험","state":"OPEN","body":"","url":"https://github.com/acme/repo/issues/42"}
                  """
                      .trimIndent(),
                  "",
              )
          )

          val result =
              GithubIssueTaskAdapter(Path.of("/repo"), runner)
                  .get(TaskLookupRequest(externalId = ExternalTaskId("42")))
          val task =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.TaskLookupResponse>()
                  .task

          task.id shouldBe "42"
          task.externalId shouldBe ExternalTaskId("42")
          task.title shouldBe "추천 알고리즘 실험"
          task.state.name shouldBe "OPEN"
          runner.commands.single().tokens shouldContainExactly
              listOf(
                  "gh",
                  "issue",
                  "view",
                  "42",
                  "--json",
                  "number,title,state,stateReason,body,url",
              )
        }
      }

      context("GitHub Issue SubTask를 조회하면") {
        test("metadata와 상태 marker를 입력하면, SubTask로 변환합니다") {
          val runner = IssueRecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  [{"number":51,"title":"경계 추가","state":"OPEN","body":"<!-- workflow-parent-task: 42 -->\n<!-- workflow-parent-external-task: 42 -->\n<!-- workflow-subtask-id: sk-101 -->\n<!-- workflow-subtask-state: REVIEW -->\n<!-- workflow-requires: sk-100 -->"}]
                  """
                      .trimIndent(),
                  "",
              )
          )

          val result =
              GithubIssueTaskAdapter(Path.of("/repo"), runner)
                  .getSubTask(SubTaskLookupRequest("sk-101"))
          val subTask =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.SubTaskLookupResponse>()
                  .subTask

          subTask.id shouldBe "sk-101"
          subTask.taskId shouldBe "42"
          subTask.state shouldBe SubTaskState.REVIEW
          subTask.requires shouldBe "sk-100"
        }
      }

      context("GitHub Issue SubTask를 생성하면") {
        test("외부 Task를 확인하고 issue를 생성하면, 생성 결과 metadata를 검증합니다") {
          val runner = IssueRecordingCommandRunner()
          runner.enqueue(
              CommandResult(0, """{"number":42,"title":"부모","state":"OPEN","body":""}""", "")
          )
          runner.enqueue(CommandResult(0, "[]", ""))
          runner.enqueue(CommandResult(0, "https://github.com/acme/repo/issues/51\n", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {"number":51,"title":"경계 추가","state":"OPEN","body":"<!-- workflow-parent-task: 42 -->\n<!-- workflow-parent-external-task: 42 -->\n<!-- workflow-subtask-id: sk-101 -->\n<!-- workflow-subtask-state: DEVELOPMENT -->"}
                  """
                      .trimIndent(),
                  "",
              )
          )

          val result =
              GithubIssueTaskAdapter(Path.of("/repo"), runner)
                  .createSubTask(
                      CreateSubTaskRequest(
                          externalTaskId = ExternalTaskId("42"),
                          subTaskId = "sk-101",
                          title = "경계 추가",
                          requestId = "start-1",
                      )
                  )
          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CreateSubTaskResponse>()

          response.task.subTaskIds shouldBe listOf("sk-101")
          response.subTask.taskId shouldBe "42"
          runner.commands.map { it.tokens.take(4) } shouldBe
              listOf(
                  listOf("gh", "issue", "view", "42"),
                  listOf("gh", "issue", "list", "--state"),
                  listOf("gh", "issue", "create", "--title"),
                  listOf("gh", "issue", "view", "https://github.com/acme/repo/issues/51"),
              )
          runner.commands[2].tokens[6] shouldContain "workflow-subtask-id: sk-101"
        }
      }

      context("외부 SubTask 상태를 갱신하면") {
        test("metadata를 보존한 채 외부 SubTask 상태를 수정하면, merge 시 issue를 닫습니다") {
          val runner = IssueRecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  [{"number":51,"title":"경계 추가","state":"OPEN","body":"<!-- workflow-parent-task: 42 -->\n<!-- workflow-parent-external-task: 42 -->\n<!-- workflow-subtask-id: sk-101 -->\n<!-- workflow-subtask-state: APPROVED -->"}]
                  """
                      .trimIndent(),
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":42,"title":"부모","state":"OPEN","body":""}""",
                  "",
              )
          )
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "", ""))

          val result =
              GithubIssueTaskAdapter(Path.of("/repo"), runner)
                  .updateSubTask(
                      UpdateExternalSubTaskRequest(
                          externalTaskId = ExternalTaskId("42"),
                          subTaskId = "sk-101",
                          state = SubTaskState.MERGED,
                          requestId = "merge-1",
                      )
                  )
          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.UpdateExternalSubTaskResponse>()

          response.state shouldBe SubTaskState.MERGED
          runner.commands.map { it.tokens.take(4) } shouldBe
              listOf(
                  listOf("gh", "issue", "list", "--state"),
                  listOf("gh", "issue", "view", "42"),
                  listOf("gh", "issue", "edit", "51"),
                  listOf("gh", "issue", "close", "51"),
              )
          runner.commands[2].tokens[5] shouldContain "workflow-subtask-state: MERGED"
        }
      }

      context("GitHub Issue 응답이 실패하면") {
        test("gh 오류가 발생하면, 재시도 가능한 PortError로 변환합니다") {
          val runner = IssueRecordingCommandRunner()
          runner.enqueue(CommandResult(2, "", "gh auth login required"))

          val result =
              GithubIssueTaskAdapter(Path.of("/repo"), runner)
                  .get(TaskLookupRequest(externalId = ExternalTaskId("42")))
          val failure = result.shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "GITHUB_TASK_COMMAND_FAILED"
          failure.error.retryable shouldBe true
          failure.error.message shouldBe "gh auth login required"
        }
      }
    })

private data class IssueInvocation(val tokens: List<String>, val workingDirectory: Path)

private class IssueRecordingCommandRunner : CommandRunner {
  private val results = ArrayDeque<() -> CommandResult>()
  val commands = mutableListOf<IssueInvocation>()

  fun enqueue(result: CommandResult) {
    results.addLast { result }
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += IssueInvocation(command, workingDirectory)
    return results.removeFirst().invoke()
  }
}
