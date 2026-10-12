package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class ApplicabilityValuesTest :
    FeatureSpec({
      feature("언어 이름") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidLanguageScenario(
                    name = "언어 이름이 빈 문자열이면, 생성이 거부됩니다.",
                    value = "",
                ),
                InvalidLanguageScenario(
                    name = "언어 이름이 공백으로만 구성되면, 생성이 거부됩니다.",
                    value = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> { Language(scenario.value) }
        }

        scenario("언어 이름이 정상 값이면, 입력을 보존합니다.") {
          val language = Language("kotlin")

          language.value shouldBe "kotlin"
        }
      }

      feature("이벤트 이름") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidGuidelineEventScenario(
                    name = "이벤트 이름이 빈 문자열이면, 생성이 거부됩니다.",
                    value = "",
                ),
                InvalidGuidelineEventScenario(
                    name = "이벤트 이름이 공백으로만 구성되면, 생성이 거부됩니다.",
                    value = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> { GuidelineEvent(scenario.value) }
        }

        scenario("이벤트 이름이 정상 값이면, 입력을 보존합니다.") {
          val event = GuidelineEvent("file_changed")

          event.value shouldBe "file_changed"
        }
      }
    })

private data class InvalidLanguageScenario(
    val name: String,
    val value: String,
)

private data class InvalidGuidelineEventScenario(
    val name: String,
    val value: String,
)
