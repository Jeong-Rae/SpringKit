package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class SessionIdTest :
    FeatureSpec({
      feature("Session 식별자") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidSessionIdScenario(
                    name = "Session 식별자가 빈 문자열이면, 생성이 거부됩니다.",
                    value = "",
                ),
                InvalidSessionIdScenario(
                    name = "Session 식별자가 공백으로만 구성되면, 생성이 거부됩니다.",
                    value = " \n\t ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            SessionId(scenario.value)
          }
        }

        scenario("Session 식별자에 앞뒤 공백이 있어도, 원본 문자열을 보존합니다.") {
          val value = " codex-session-123 "

          val sessionId = SessionId(value)

          sessionId.value shouldBe value
        }
      }
    })

private data class InvalidSessionIdScenario(
    val name: String,
    val value: String,
)
