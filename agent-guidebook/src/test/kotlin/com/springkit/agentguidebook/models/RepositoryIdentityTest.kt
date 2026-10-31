package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class RepositoryIdentityTest :
    FeatureSpec({
      feature("저장소 소유자") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidRepositoryOwnerScenario(
                    name = "저장소 소유자가 빈 문자열이면, 식별자 생성이 거부됩니다.",
                    owner = "",
                ),
                InvalidRepositoryOwnerScenario(
                    name = "저장소 소유자가 공백으로만 구성되면, 식별자 생성이 거부됩니다.",
                    owner = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            RepositoryIdentity(scenario.owner, "backend-order")
          }
        }

        scenario("저장소 소유자가 정상 값이면, 식별자에 입력을 보존합니다.") {
          val identity = RepositoryIdentity("acme", "backend-order")

          identity.owner shouldBe "acme"
        }
      }

      feature("저장소 이름") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidRepositoryNameScenario(
                    name = "저장소 이름이 빈 문자열이면, 식별자 생성이 거부됩니다.",
                    repository = "",
                ),
                InvalidRepositoryNameScenario(
                    name = "저장소 이름이 공백으로만 구성되면, 식별자 생성이 거부됩니다.",
                    repository = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            RepositoryIdentity("acme", scenario.repository)
          }
        }

        scenario("저장소 이름이 정상 값이면, 식별자에 입력을 보존합니다.") {
          val identity = RepositoryIdentity("acme", "backend-order")

          identity.repository shouldBe "backend-order"
        }
      }

      feature("정규화된 저장소 식별자") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidRepositoryIdentityScenario(
                    name = "저장소 소유자에 슬래시가 있으면, 식별자 생성이 거부됩니다.",
                    owner = "ac/me",
                    repository = "backend-order",
                ),
                InvalidRepositoryIdentityScenario(
                    name = "저장소 이름에 슬래시가 있으면, 식별자 생성이 거부됩니다.",
                    owner = "acme",
                    repository = "backend/order",
                ),
                InvalidRepositoryIdentityScenario(
                    name = "저장소 소유자에 공백이 있으면, 식별자 생성이 거부됩니다.",
                    owner = "ac me",
                    repository = "backend-order",
                ),
                InvalidRepositoryIdentityScenario(
                    name = "저장소 이름에 공백이 있으면, 식별자 생성이 거부됩니다.",
                    owner = "acme",
                    repository = "backend order",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            RepositoryIdentity(scenario.owner, scenario.repository)
          }
        }
      }

      feature("저장소 식별자의 정규 이름") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                QualifiedNameScenario(
                    name = "ASCII 저장소 구성값이면, 입력 표현을 연결해 반환합니다.",
                    owner = "acme",
                    repository = "backend-order",
                    expected = "acme/backend-order",
                ),
                QualifiedNameScenario(
                    name = "한글 저장소 구성값이면, 입력 표현을 보존해 연결합니다.",
                    owner = "조직",
                    repository = "결제서비스",
                    expected = "조직/결제서비스",
                ),
                QualifiedNameScenario(
                    name = "구성값에 정규식 메타문자가 있으면, 메타문자를 그대로 보존합니다.",
                    owner = "acme.+",
                    repository = "service(standard)",
                    expected = "acme.+/service(standard)",
                ),
            ),
        ) { scenario ->
          val identity = RepositoryIdentity(scenario.owner, scenario.repository)

          val qualifiedName = identity.qualifiedName()

          qualifiedName shouldBe scenario.expected
        }
      }
    })

private data class InvalidRepositoryOwnerScenario(
    val name: String,
    val owner: String,
)

private data class InvalidRepositoryNameScenario(
    val name: String,
    val repository: String,
)

private data class InvalidRepositoryIdentityScenario(
    val name: String,
    val owner: String,
    val repository: String,
)

private data class QualifiedNameScenario(
    val name: String,
    val owner: String,
    val repository: String,
    val expected: String,
)
