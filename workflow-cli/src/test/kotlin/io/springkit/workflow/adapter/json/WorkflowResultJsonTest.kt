package io.springkit.workflow.adapter.json

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        JsonRenderer.render(result.toJsonValue()),
    )
  }

  @Test
  fun `success places next actions inside data`() {
    val result =
        WorkflowResult.Success(
            JsonValue.Object.of("subtask" to "sk-1".toJson()),
            listOf(NextAction(ActorKind.AGENT, "implement_change")),
        )

    assertEquals(
        "{\"data\":{\"next\":[{\"action\":\"implement_change\",\"actor\":\"agent\"}],\"subtask\":\"sk-1\"},\"type\":\"success\"}",
        JsonRenderer.render(result.toJsonValue()),
    )
  }

  @Test
  fun `typed accessors reject missing and invalid values`() {
    val value =
        JsonParser.parse("{\"name\":\"workflow\",\"enabled\":true,\"count\":2}").requireObject()

    assertEquals("workflow", value.string("name"))
    assertEquals(true, value.boolean("enabled"))
    assertEquals(2, value.long("count"))
    assertFailsWith<JsonDecodeException> { value.string("missing") }
    assertFailsWith<JsonDecodeException> { value.boolean("name") }
  }
}
