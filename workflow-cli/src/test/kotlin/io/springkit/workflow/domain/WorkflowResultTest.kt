package io.springkit.workflow.domain

import io.springkit.workflow.adapter.json.JsonRenderer
import io.springkit.workflow.adapter.json.toJsonValue
import kotlin.test.Test
import kotlin.test.assertContains

class WorkflowResultTest {
  @Test
  fun `documented failure codes keep their stable json names`() {
    val result =
        WorkflowResult.Failure(
            FailureData(
                code = FailureCode.IDEMPOTENCY_CONFLICT,
                message = "같은 요청 키의 입력이 다릅니다.",
            )
        )

    assertContains(
        JsonRenderer.render(result.toJsonValue()),
        "\"code\":\"IDEMPOTENCY_CONFLICT\"",
    )
  }

  @Test
  fun `human and synchronization failures are explicit domain values`() {
    val codes = FailureCode.entries.toSet()

    assertContains(codes, FailureCode.HUMAN_REQUIRED)
    assertContains(codes, FailureCode.SYNC_CONFLICT)
    assertContains(codes, FailureCode.STALE_DIFF_IDENTITY)
  }
}
