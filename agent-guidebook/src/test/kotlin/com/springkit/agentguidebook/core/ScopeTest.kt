package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class ScopeTest :
    FeatureSpec({
      feature("저장소 적용 범위") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                ScopeScenario(
                    name = "저장소 패턴의 접두사와 저장소가 일치할 때 적용 범위를 확인하면, 범위가 일치합니다.",
                    repositories = listOf("acme/backend-*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("acme", "backend-order"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = true,
                ),
                ScopeScenario(
                    name = "저장소 이름이 패턴의 접두사와 같고 뒤의 값이 없을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repositories = listOf("acme/backend-*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("acme", "backend"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
                ScopeScenario(
                    name = "저장소 소유자가 패턴과 다를 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repositories = listOf("acme/backend-*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("other", "backend-order"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
                ScopeScenario(
                    name = "저장소 패턴이 별표 하나일 때 적용 범위를 확인하면, 다른 저장소도 범위에 포함됩니다.",
                    repositories = listOf("*"),
                    languages = listOf("kotlin"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("other", "service"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = true,
                ),
                ScopeScenario(
                    name = "저장소 패턴의 마침표와 더하기 기호가 저장소에 있을 때 적용 범위를 확인하면, 문자 그대로 일치합니다.",
                    repositories = listOf("acme/backend.+*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("acme", "backend.+service"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = true,
                ),
                ScopeScenario(
                    name = "저장소에 패턴의 마침표와 더하기 기호가 없을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repositories = listOf("acme/backend.+*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("acme", "backend.zzservice"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
                ScopeScenario(
                    name = "저장소 이름 앞에 패턴에 없는 문자가 있을 때 적용 범위를 확인하면, 전체 식별자가 일치하지 않습니다.",
                    repositories = listOf("acme/backend.+*"),
                    languages = listOf("*"),
                    context =
                        ApplicabilityContext(
                            RepositoryIdentity("acme", "prefix-backend.+service"),
                            Language("kotlin"),
                            GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
            ),
        ) { scenario ->
          val scope = Scope(scenario.repositories, scenario.languages)

          val matches = scope.matches(scenario.context)

          matches shouldBe scenario.expected
        }
      }

      feature("언어 적용 범위") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                LanguageScopeScenario(
                    name = "언어가 목록에 있을 때 적용 범위를 확인하면, 범위가 일치합니다.",
                    languages = listOf("kotlin", "java"),
                    language = Language("java"),
                    expected = true,
                ),
                LanguageScopeScenario(
                    name = "언어가 목록에 없고 전체 언어 조건도 없을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    languages = listOf("kotlin"),
                    language = Language("java"),
                    expected = false,
                ),
                LanguageScopeScenario(
                    name = "언어 목록이 별표 하나일 때 적용 범위를 확인하면, 모든 언어가 범위에 포함됩니다.",
                    languages = listOf("*"),
                    language = Language("java"),
                    expected = true,
                ),
            ),
        ) { scenario ->
          val scope = Scope(listOf("acme/backend-order"), scenario.languages)
          val context =
              ApplicabilityContext(
                  repository = RepositoryIdentity("acme", "backend-order"),
                  language = scenario.language,
                  event = GuidelineEvent("file_changed"),
              )

          val matches = scope.matches(context)

          matches shouldBe scenario.expected
        }
      }

      feature("범위 조건의 조합") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                CombinedScopeScenario(
                    name = "저장소 목록의 한 값과 언어 목록의 한 값이 일치할 때 적용 범위를 확인하면, 범위가 일치합니다.",
                    repository = RepositoryIdentity("acme", "backend-order"),
                    language = Language("kotlin"),
                    expected = true,
                ),
                CombinedScopeScenario(
                    name = "저장소와 언어가 각각 다른 목록 값과 일치할 때 적용 범위를 확인하면, 범위가 일치합니다.",
                    repository = RepositoryIdentity("acme", "other"),
                    language = Language("java"),
                    expected = true,
                ),
                CombinedScopeScenario(
                    name = "저장소 목록에 일치하는 값이 없을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repository = RepositoryIdentity("acme", "missing"),
                    language = Language("kotlin"),
                    expected = false,
                ),
                CombinedScopeScenario(
                    name = "언어 목록에 일치하는 값이 없을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repository = RepositoryIdentity("acme", "backend-order"),
                    language = Language("scala"),
                    expected = false,
                ),
            ),
        ) { scenario ->
          val scope =
              Scope(
                  repositories = listOf("acme/other", "acme/backend-order"),
                  languages = listOf("java", "kotlin"),
              )
          val context =
              ApplicabilityContext(
                  repository = scenario.repository,
                  language = scenario.language,
                  event = GuidelineEvent("file_changed"),
              )

          val matches = scope.matches(context)

          matches shouldBe scenario.expected
        }
      }

      feature("빈 적용 범위") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                EmptyScopeScenario(
                    name = "저장소 목록이 비어 있을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repositories = emptyList(),
                    languages = listOf("*"),
                ),
                EmptyScopeScenario(
                    name = "언어 목록이 비어 있을 때 적용 범위를 확인하면, 범위가 일치하지 않습니다.",
                    repositories = listOf("*"),
                    languages = emptyList(),
                ),
            ),
        ) { scenario ->
          val scope = Scope(scenario.repositories, scenario.languages)
          val context =
              ApplicabilityContext(
                  repository = RepositoryIdentity("acme", "backend-order"),
                  language = Language("kotlin"),
                  event = GuidelineEvent("file_changed"),
              )

          val matches = scope.matches(context)

          matches shouldBe false
        }
      }

      feature("공백 범위 조건 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidScopeScenario(
                    name = "저장소 조건에 빈 문자열이 포함된 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf(""),
                    languages = listOf("kotlin"),
                ),
                InvalidScopeScenario(
                    name = "공백으로만 이루어진 저장소 조건을 포함한 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf(" "),
                    languages = listOf("kotlin"),
                ),
                InvalidScopeScenario(
                    name = "줄바꿈으로만 이루어진 저장소 조건을 포함한 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("\n"),
                    languages = listOf("kotlin"),
                ),
                InvalidScopeScenario(
                    name = "유효한 저장소 조건과 공백 조건이 함께 있는 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("acme/backend-order", " "),
                    languages = listOf("kotlin"),
                ),
                InvalidScopeScenario(
                    name = "언어 조건에 빈 문자열이 포함된 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("acme/backend-order"),
                    languages = listOf(""),
                ),
                InvalidScopeScenario(
                    name = "공백으로만 이루어진 언어 조건을 포함한 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("acme/backend-order"),
                    languages = listOf(" "),
                ),
                InvalidScopeScenario(
                    name = "줄바꿈으로만 이루어진 언어 조건을 포함한 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("acme/backend-order"),
                    languages = listOf("\n"),
                ),
                InvalidScopeScenario(
                    name = "유효한 언어 조건과 공백 조건이 함께 있는 Scope를 생성하면, 생성이 거부됩니다.",
                    repositories = listOf("acme/backend-order"),
                    languages = listOf("kotlin", "\n"),
                ),
            ),
        ) { scenario ->
          val repositories = scenario.repositories
          val languages = scenario.languages

          shouldThrow<IllegalArgumentException> {
            Scope(repositories, languages)
          }
        }
      }

      feature("범위 컬렉션 불변성") {
        scenario("생성에 사용한 저장소 목록을 비우면, 범위에 저장된 조건은 유지됩니다.") {
          val suppliedRepositories = mutableListOf("acme/backend-order")
          val scope = Scope(suppliedRepositories, listOf("kotlin"))
          val context =
              ApplicabilityContext(
                  repository = RepositoryIdentity("acme", "backend-order"),
                  language = Language("kotlin"),
                  event = GuidelineEvent("file_changed"),
              )

          suppliedRepositories.clear()

          scope.matches(context) shouldBe true
        }

        scenario("생성에 사용한 언어 목록을 비우면, 범위에 저장된 조건은 유지됩니다.") {
          val suppliedLanguages = mutableListOf("kotlin")
          val scope = Scope(listOf("acme/backend-order"), suppliedLanguages)
          val context =
              ApplicabilityContext(
                  repository = RepositoryIdentity("acme", "backend-order"),
                  language = Language("kotlin"),
                  event = GuidelineEvent("file_changed"),
              )

          suppliedLanguages.clear()

          scope.matches(context) shouldBe true
        }

        scenario("공개된 저장소 목록에 값을 추가하면, 수정이 거부됩니다.") {
          val scope = Scope(listOf("acme/backend-order"), listOf("kotlin"))

          shouldThrow<UnsupportedOperationException> {
            (scope.repositories as MutableList).add("*")
          }

          scope.repositories shouldBe listOf("acme/backend-order")
        }

        scenario("공개된 언어 목록에 값을 추가하면, 수정이 거부됩니다.") {
          val scope = Scope(listOf("acme/backend-order"), listOf("kotlin"))

          shouldThrow<UnsupportedOperationException> { (scope.languages as MutableList).add("*") }

          scope.languages shouldBe listOf("kotlin")
        }
      }
    })

private data class ScopeScenario(
    val name: String,
    val repositories: List<String>,
    val languages: List<String>,
    val context: ApplicabilityContext,
    val expected: Boolean,
)

private data class LanguageScopeScenario(
    val name: String,
    val languages: List<String>,
    val language: Language,
    val expected: Boolean,
)

private data class CombinedScopeScenario(
    val name: String,
    val repository: RepositoryIdentity,
    val language: Language,
    val expected: Boolean,
)

private data class EmptyScopeScenario(
    val name: String,
    val repositories: List<String>,
    val languages: List<String>,
)

private data class InvalidScopeScenario(
    val name: String,
    val repositories: List<String>,
    val languages: List<String>,
)
