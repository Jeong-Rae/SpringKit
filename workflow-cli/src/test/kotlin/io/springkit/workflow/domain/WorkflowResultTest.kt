package io.springkit.workflow.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.springkit.workflow.adapter.json.encodeToString

class WorkflowResultTest :
    FunSpec({
      context("WorkflowResult의 실패 코드 JSON 이름을 확인할 때") {
        test("기록된 실패 코드이면, 안정적인 JSON 이름을 유지합니다") {
          val result =
              WorkflowResult.Failure(
                  FailureData(
                      code = FailureCode.IDEMPOTENCY_CONFLICT,
                      message = "같은 요청 키의 입력이 다릅니다.",
                  )
              )

          result.encodeToString() shouldContain "\"code\":\"IDEMPOTENCY_CONFLICT\""
        }
      }

      context("FailureCode의 명시적 도메인 값을 확인할 때") {
        test("사람 승인과 동기화 실패 코드이면, enum에 포함됩니다") {
          val codes = FailureCode.entries.toSet()

          codes.contains(FailureCode.HUMAN_REQUIRED) shouldBe true
          codes.contains(FailureCode.SYNC_CONFLICT) shouldBe true
          codes.contains(FailureCode.STALE_DIFF_IDENTITY) shouldBe true
        }
      }
    })
