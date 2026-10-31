package com.springkit.agentguidebook.errors

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
                    name = "지침 로딩 오류에 원인이 있으면, 예외가 원인을 보존합니다.",
                    create = { message, cause -> GuidelineLoadException(message, cause) },
                    message = "지침 YAML을 해석할 수 없습니다.",
                ),
                SearchExceptionScenario(
                    name = "저장소 식별 오류에 원인이 있으면, 예외가 원인을 보존합니다.",
                    create = { message, cause ->
                      RepositoryIdentityUnavailableException(message, cause)
                    },
                    message = "저장소 origin을 확인할 수 없습니다.",
                ),
                SearchExceptionScenario(
                    name = "소스 읽기 오류에 원인이 있으면, 예외가 원인을 보존합니다.",
                    create = { message, cause -> SourceReadException(message, cause) },
                    message = "소스 파일을 읽을 수 없습니다.",
                ),
            ),
        ) { scenario ->
          val cause = IllegalStateException("원인 오류")

          val exception = scenario.create(scenario.message, cause)

          exception.message shouldBe scenario.message
          exception.cause shouldBe cause
        }
      }

      feature("검색 오류 기본 메시지") {
        scenario("검색 요청 오류를 기본값으로 만들면, 한글 메시지가 제공됩니다.") {
          InvalidSearchRequestException().message shouldBe "검색 요청이 올바르지 않습니다."
        }

        scenario("지침 로딩 오류를 기본값으로 만들면, 한글 메시지가 제공됩니다.") {
          GuidelineLoadException().message shouldBe "지침 파일을 읽을 수 없습니다."
        }

        scenario("저장소 식별 오류를 기본값으로 만들면, 한글 메시지가 제공됩니다.") {
          RepositoryIdentityUnavailableException().message shouldBe "저장소 식별자를 확인할 수 없습니다."
        }

        scenario("소스 읽기 오류를 기본값으로 만들면, 한글 메시지가 제공됩니다.") {
          SourceReadException().message shouldBe "검색 대상 소스 파일을 읽을 수 없습니다."
        }
      }
    })

private data class SearchExceptionScenario(
    val name: String,
    val create: (String, Throwable) -> RuntimeException,
    val message: String,
)
