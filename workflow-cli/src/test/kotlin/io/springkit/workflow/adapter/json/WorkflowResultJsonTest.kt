package io.springkit.workflow.adapter.json

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkflowResultJsonTest {
  @Test
  fun `failure uses the common JSON result contract`() {
    val result =
        WorkflowResult.Failure(
            FailureData(
                code = FailureCode.HUMAN_REQUIRED,
                message = "사람의 결정이 필요합니다.",
                blockedBy = listOf(BlockedBy("HUMAN_REQUIRED", "사람만 실행할 수 있습니다.", "sk-1")),
                next =
                    listOf(
                        NextAction(ActorKind.HUMAN, "approve_change", "workflow gate approve sk-1")
                    ),
            ),
        )

    assertEquals(
        "{\"data\":{\"blocked_by\":[{\"code\":\"HUMAN_REQUIRED\",\"message\":\"사람만 실행할 수 있습니다.\",\"target\":\"sk-1\"}],\"code\":\"HUMAN_REQUIRED\",\"message\":\"사람의 결정이 필요합니다.\",\"next\":[{\"action\":\"approve_change\",\"actor\":\"human\",\"command\":\"workflow gate approve sk-1\"}]},\"type\":\"failure\"}",
        result.encodeToString(),
    )
  }

  @Test
  fun `success places next actions inside data`() {
    val result =
        WorkflowResult.Success(
            buildJsonObject { put("subtask", JsonPrimitive("sk-1")) },
            listOf(NextAction(ActorKind.AGENT, "implement_change")),
        )

    assertEquals(
        "{\"data\":{\"next\":[{\"action\":\"implement_change\",\"actor\":\"agent\"}],\"subtask\":\"sk-1\"},\"type\":\"success\"}",
        result.encodeToString(),
    )
  }

  @Test
  fun `kotlinx json parses and renders the workflow contract`() {
    val value = Json.parseToJsonElement("{\"type\":\"success\",\"data\":{\"ok\":true}}")

    assertEquals("{\"type\":\"success\",\"data\":{\"ok\":true}}", value.toString())
  }
}
