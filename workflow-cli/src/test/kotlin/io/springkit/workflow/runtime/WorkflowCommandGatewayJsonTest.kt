package io.springkit.workflow.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.springkit.workflow.application.StackResponse
import io.springkit.workflow.application.SyncResponse
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class WorkflowCommandGatewayJsonTest :
    FunSpec({
      context("stack 성공 결과를 JSON으로 변환할 때") {
        test("실제 diff가 바뀌면 invalidated_state에 무효화된 상태를 반환합니다") {
          val response = responseStack(invalidatedState = listOf("change_revision", "approval"))

          stackResponseJson(response)["invalidated_state"].toString() shouldBe
              "[\"change_revision\",\"approval\"]"
        }
      }

      context("sync 성공 결과를 JSON으로 변환할 때") {
        test("diff가 유지되면 빈 invalidated_state를 반환합니다") {
          val response = responseSync(invalidatedState = emptyList())

          syncResponseJson(response)["invalidated_state"].toString() shouldBe "[]"
        }
      }
    })

private fun responseStack(invalidatedState: List<String>): StackResponse {
  val subTask = responseSubTask()
  return StackResponse(
      subTask = subTask,
      workspace = requireNotNull(subTask.workspace),
      baseBranch = "main",
      baseRevision = "main-1",
      invalidatedState = invalidatedState,
  )
}

private fun responseSync(invalidatedState: List<String>): SyncResponse {
  val subTask = responseSubTask()
  return SyncResponse(
      subTask = subTask,
      workspace = requireNotNull(subTask.workspace),
      baseBranch = "main",
      baseRevision = "main-1",
      invalidatedState = invalidatedState,
  )
}

private fun responseSubTask(): SubTask =
    SubTask(
        id = "sk-101",
        taskId = "task-1",
        title = "변경",
        workspace = Workspace("ws-101", "sk-101", WorkspacePath("/repo/sk-101"), "sk-101"),
    )
