package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class GuidelineIdTest :
    FeatureSpec({
      feature("지침 ID") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidGuidelineIdScenario(
                    name = "ID가 빈 문자열이면, 지침 ID 생성이 거부됩니다.",
                    value = "",
                ),
                InvalidGuidelineIdScenario(
                    name = "ID가 공백으로만 구성되면, 지침 ID 생성이 거부됩니다.",
                    value = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> { GuidelineId(scenario.value) }
        }

        scenario("ID가 한글을 포함한 값이면, 입력을 지침 ID에 보존합니다.") {
          val guidelineId = GuidelineId("서비스-규칙")

          guidelineId.value shouldBe "서비스-규칙"
        }
      }
    })

private data class InvalidGuidelineIdScenario(
    val name: String,
    val value: String,
)
