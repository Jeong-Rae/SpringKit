package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe

class GuidelineTest :
    FeatureSpec({
      feature("지침의 적용 조건") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                ApplicabilityScenario(
                    name = "저장소, 언어, 이벤트가 모두 일치하는 컨텍스트로 적용 여부를 확인하면, 지침이 적용됩니다.",
                    context =
                        ApplicabilityContext(
                            repository = RepositoryIdentity("acme", "backend-order"),
                            language = Language("kotlin"),
                            event = GuidelineEvent("file_changed"),
                        ),
                    expected = true,
                ),
                ApplicabilityScenario(
                    name = "저장소가 다른 컨텍스트로 적용 여부를 확인하면, 지침이 적용되지 않습니다.",
                    context =
                        ApplicabilityContext(
                            repository = RepositoryIdentity("other", "backend-order"),
                            language = Language("kotlin"),
                            event = GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
                ApplicabilityScenario(
                    name = "언어가 다른 컨텍스트로 적용 여부를 확인하면, 지침이 적용되지 않습니다.",
                    context =
                        ApplicabilityContext(
                            repository = RepositoryIdentity("acme", "backend-order"),
                            language = Language("java"),
                            event = GuidelineEvent("file_changed"),
                        ),
                    expected = false,
                ),
                ApplicabilityScenario(
                    name = "이벤트가 다른 컨텍스트로 적용 여부를 확인하면, 지침이 적용되지 않습니다.",
                    context =
                        ApplicabilityContext(
                            repository = RepositoryIdentity("acme", "backend-order"),
                            language = Language("kotlin"),
                            event = GuidelineEvent("file_created"),
                        ),
                    expected = false,
                ),
            ),
        ) { scenario ->
          val guideline =
              Guideline(
                  id = GuidelineId("서비스 규칙"),
                  scope = Scope(listOf("acme/backend-*"), listOf("kotlin")),
                  events = setOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(listOf("RepositoryImpl")),
                  content = "서비스는 저장소 추상화에 의존해야 합니다.",
              )

          val isApplicable = guideline.isApplicable(scenario.context)

          isApplicable shouldBe scenario.expected
        }

        scenario("이벤트 목록이 빈 지침의 적용 여부를 확인하면, 지침이 적용되지 않습니다.") {
          val guideline =
              Guideline(
                  id = GuidelineId("이벤트 없는 규칙"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = emptySet(),
                  match = MatchCondition(emptyList()),
                  content = "이벤트가 없으면 적용할 수 없습니다.",
              )
          val context =
              ApplicabilityContext(
                  repository = RepositoryIdentity("acme", "backend-order"),
                  language = Language("kotlin"),
                  event = GuidelineEvent("file_changed"),
              )

          val isApplicable = guideline.isApplicable(context)

          isApplicable shouldBe false
        }
      }

      feature("소스 코드 관련성") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                GuidelineSourceScenario(
                    name = "소스 코드에 RepositoryImpl이 포함될 때 관련성을 평가하면, 지침이 일치합니다.",
                    source = "class OrderRepositoryImpl",
                    expected = true,
                ),
                GuidelineSourceScenario(
                    name = "소스 코드에 RepositoryImpl이 없을 때 관련성을 평가하면, 지침이 일치하지 않습니다.",
                    source = "class OrderRepository",
                    expected = false,
                ),
            ),
        ) { scenario ->
          val guideline =
              Guideline(
                  id = GuidelineId("저장소 구현 규칙"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = setOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(listOf("RepositoryImpl")),
                  content = "서비스는 저장소 추상화에 의존해야 합니다.",
              )

          val matches = guideline.matches(scenario.source)

          matches shouldBe scenario.expected
        }
      }

      feature("지침 본문") {
        scenario("본문이 한글 문장인 지침을 만들면, 본문이 보존됩니다.") {
          val guideline =
              Guideline(
                  id = GuidelineId("본문 규칙"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = setOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(emptyList()),
                  content = "서비스는 저장소 추상화에 의존해야 합니다.",
              )

          guideline.content shouldBe "서비스는 저장소 추상화에 의존해야 합니다."
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidContentScenario(
                    name = "본문이 빈 문자열인 지침을 만들면, 생성이 거부됩니다.",
                    content = "",
                ),
                InvalidContentScenario(
                    name = "본문이 공백으로만 구성된 지침을 만들면, 생성이 거부됩니다.",
                    content = " \n ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            Guideline(
                id = GuidelineId("본문 규칙"),
                scope = Scope(listOf("*"), listOf("*")),
                events = setOf(GuidelineEvent("file_changed")),
                match = MatchCondition(emptyList()),
                content = scenario.content,
            )
          }
        }
      }

      feature("지침 동일성") {
        scenario("내용과 조건이 달라도 ID가 같으면, 두 지침은 동등합니다.") {
          val first =
              Guideline(
                  id = GuidelineId("같은 ID"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = setOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(listOf("RepositoryImpl")),
                  content = "첫 번째 본문",
              )
          val second =
              Guideline(
                  id = GuidelineId("같은 ID"),
                  scope = Scope(listOf("acme/backend-order"), listOf("java")),
                  events = emptySet(),
                  match = MatchCondition(listOf("Service")),
                  content = "두 번째 본문",
              )

          val areEqual = first == second

          areEqual shouldBe true
          first.hashCode() shouldBe second.hashCode()
        }
      }

      feature("이벤트 컬렉션 불변성") {
        scenario("생성에 사용한 이벤트 집합을 비우면, 지침에 저장된 이벤트는 유지됩니다.") {
          val suppliedEvents = mutableSetOf(GuidelineEvent("file_changed"))
          val guideline =
              Guideline(
                  id = GuidelineId("이벤트 복사 규칙"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = suppliedEvents,
                  match = MatchCondition(emptyList()),
                  content = "입력 이벤트 집합과 독립적으로 유지합니다.",
              )

          suppliedEvents.clear()

          guideline.events shouldBe setOf(GuidelineEvent("file_changed"))
        }

        scenario("공개된 이벤트 집합에 값을 추가하면, 수정이 거부됩니다.") {
          val guideline =
              Guideline(
                  id = GuidelineId("이벤트 공개 규칙"),
                  scope = Scope(listOf("*"), listOf("*")),
                  events = setOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(emptyList()),
                  content = "공개 집합으로 내부 이벤트를 바꿀 수 없습니다.",
              )

          shouldThrow<UnsupportedOperationException> {
            (guideline.events as MutableSet<GuidelineEvent>).add(GuidelineEvent("file_created"))
          }

          guideline.events shouldBe setOf(GuidelineEvent("file_changed"))
        }
      }
    })

private data class ApplicabilityScenario(
    val name: String,
    val context: ApplicabilityContext,
    val expected: Boolean,
)

private data class GuidelineSourceScenario(
    val name: String,
    val source: String,
    val expected: Boolean,
)

private data class InvalidContentScenario(
    val name: String,
    val content: String,
)
