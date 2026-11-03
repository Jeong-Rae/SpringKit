package com.springkit.agentguidebook.search

import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class SearchExceptionContractTest :
    FeatureSpec({
      feature("검색 오류 원인 보존") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                SearchExceptionScenario(
                    name = "검색 요청 오류에 원인이 있으면, 예외가 원인을 보존합니다.",
                    create = { message, cause -> InvalidSearchRequestException(message, cause) },
                    message = "검색 요청 경로가 올바르지 않습니다.",
                ),
                SearchExceptionScenario(
                    name = "저장소 식별 오류에 원인이 있으면, 예외가 원인을 보존합니다.",
                    create = { message, cause ->
                      RepositoryIdentityUnavailableException(message, cause)
                    },
                    message = "저장소 origin을 확인할 수 없습니다.",
                ),
            ),
        ) { scenario ->
          val cause = IllegalStateException("원인 오류")

          val exception = scenario.create(scenario.message, cause)

          exception.message shouldBe scenario.message
          exception.cause shouldBe cause
        }
      }
    })

private data class SearchExceptionScenario(
    val name: String,
    val create: (String, Throwable) -> RuntimeException,
    val message: String,
)
