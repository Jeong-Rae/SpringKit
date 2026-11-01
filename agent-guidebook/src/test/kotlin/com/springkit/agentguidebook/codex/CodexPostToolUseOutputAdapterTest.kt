package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelineId
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexPostToolUseOutputAdapterTest :
    FeatureSpec({
      val adapter = CodexPostToolUseOutputAdapter()

      feature("Codex PostToolUse 지침 응답") {
        scenario("여러 지침을 렌더링하면, 검색 순서와 Context 형식을 유지합니다.") {
          val guidelines =
              listOf(
                  MatchedGuideline(
                      id = GuidelineId("first-rule"),
                      content = "첫 번째 지침 본문입니다.",
                  ),
                  MatchedGuideline(
                      id = GuidelineId("second-rule"),
                      content = "두 번째 지침 본문입니다.",
                  ),
              )

          val response = adapter.render(guidelines).shouldNotBeNull()
          val root = Json.parseToJsonElement(response).jsonObject
          val hookSpecificOutput = root.getValue("hookSpecificOutput").jsonObject

          root.keys shouldBe setOf("hookSpecificOutput")
          hookSpecificOutput.keys shouldBe setOf("hookEventName", "additionalContext")
          hookSpecificOutput.getValue("hookEventName").jsonPrimitive.content shouldBe "PostToolUse"
          hookSpecificOutput.getValue("additionalContext").jsonPrimitive.content shouldBe
              "[first-rule]\n첫 번째 지침 본문입니다.\n\n[second-rule]\n두 번째 지침 본문입니다."
        }

        scenario("지침 목록이 비어 있으면, 응답을 만들지 않습니다.") {
          adapter.render(emptyList()) shouldBe null
        }

        scenario("Context에 한글과 따옴표, 역슬래시, 줄바꿈이 있으면, JSON roundtrip 뒤 원문을 보존합니다.") {
          val content = "한글 \"따옴표\", 역슬래시 \\와 줄바꿈\n다음 줄입니다."
          val guideline =
              MatchedGuideline(
                  id = GuidelineId("special-rule"),
                  content = content,
              )

          val response = adapter.render(listOf(guideline)).shouldNotBeNull()
          val root = Json.parseToJsonElement(response).jsonObject
          val hookSpecificOutput = root.getValue("hookSpecificOutput").jsonObject
          val additionalContext =
              hookSpecificOutput.getValue("additionalContext").jsonPrimitive.content

          additionalContext shouldBe "[special-rule]\n$content"
        }
      }
    })
