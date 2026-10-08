package io.springkit.workflow.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReviewBodyTemplateTest :
    FunSpec({
      context("PR 본문 Markdown 제목을 검증하는 상황에서") {
        test("필수 제목이 명세 순서로 있으면, 템플릿 검증을 통과합니다") {
          ReviewBodyTemplate.validate(validReviewBody()) shouldBe null
        }

        test("필수 제목이 없으면, 누락된 제목을 반환합니다") {
          val body = validReviewBody().replace("## 왜 지금 해결해야 하는가", "## 다른 제목")

          ReviewBodyTemplate.validate(body) shouldBe "PR 본문에 '왜 지금 해결해야 하는가' 제목이 없습니다."
        }

        test("필수 제목 순서가 바뀌면, 기대한 순서를 반환합니다") {
          val body =
              validReviewBody()
                  .replace("## 해결하려는 문제", "## 임시 제목")
                  .replace("## 왜 지금 해결해야 하는가", "## 해결하려는 문제")
                  .replace("## 임시 제목", "## 왜 지금 해결해야 하는가")

          ReviewBodyTemplate.validate(body) shouldBe
              "PR 본문 제목 순서가 잘못되었습니다. '해결하려는 문제' 다음에 '왜 지금 해결해야 하는가' 제목이 있어야 합니다."
        }

        test("공백 없이 정보 문자열이 붙은 코드 펜스 안의 제목은, 필수 제목으로 인정하지 않습니다") {
          val body = "```kotlin\n## 해결하려는 문제\n```"

          ReviewBodyTemplate.validate(body) shouldBe "PR 본문에 '해결하려는 문제' 제목이 없습니다."
        }
      }
    })

private fun validReviewBody(): String =
    listOf(
            "해결하려는 문제",
            "왜 지금 해결해야 하는가",
            "어떻게 해결했는가",
            "한계와 트레이드오프",
            "기존 기능에 미치는 영향",
            "Edge Case와 실패 시나리오",
            "검토한 대안과 선택 이유",
            "리뷰 포인트",
        )
        .joinToString("\n\n") { heading -> "## $heading\n설명" }
