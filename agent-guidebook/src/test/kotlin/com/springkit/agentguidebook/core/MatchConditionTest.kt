package com.springkit.agentguidebook.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import java.util.regex.PatternSyntaxException

class MatchConditionTest :
    FeatureSpec({
      feature("정규식 부분 일치") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                MatchConditionSourceScenario(
                    name = "소스 코드에 RepositoryImpl이 포함될 때 관련성을 평가하면, 정규식 부분 일치가 성공합니다.",
                    source = "class OrderRepositoryImpl",
                    expected = true,
                ),
                MatchConditionSourceScenario(
                    name = "소스 코드에 RepositoryImpl이 없을 때 관련성을 평가하면, 정규식 부분 일치가 실패합니다.",
                    source = "class OrderRepository",
                    expected = false,
                ),
            ),
        ) { scenario ->
          val matchCondition = MatchCondition(listOf("RepositoryImpl"))

          val matches = matchCondition.matches(scenario.source)

          matches shouldBe scenario.expected
        }

        scenario("정규식 조건이 비어 있을 때 소스 코드 관련성을 평가하면, 일치하지 않습니다.") {
          val matchCondition = MatchCondition(emptyList())

          val matches = matchCondition.matches("임의의 소스 코드")

          matches shouldBe false
        }
      }

      feature("정규식 개수") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                PatternCountScenario(
                    name = "정규식을 0개 지정하고 조건을 만들면, 빈 조건이 생성됩니다.",
                    patterns = emptyList(),
                    expectedCount = 0,
                ),
                PatternCountScenario(
                    name = "정규식을 1개 지정하고 조건을 만들면, 조건이 생성됩니다.",
                    patterns = listOf("RepositoryImpl"),
                    expectedCount = 1,
                ),
            ),
        ) { scenario ->
          val matchCondition = MatchCondition(scenario.patterns)

          matchCondition.patterns.size shouldBe scenario.expectedCount
        }

        scenario("정규식을 2개 지정하고 조건을 만들면, 생성이 거부됩니다.") {
          val patterns = listOf("one", "two")

          shouldThrow<IllegalArgumentException> { MatchCondition(patterns) }
        }
      }

      feature("정규식 필수값") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                BlankPatternScenario(
                    name = "정규식이 빈 문자열이면, 조건 생성이 거부됩니다.",
                    pattern = "",
                ),
                BlankPatternScenario(
                    name = "정규식이 공백으로만 구성되면, 조건 생성이 거부됩니다.",
                    pattern = "  ",
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> { MatchCondition(listOf(scenario.pattern)) }
        }

        scenario("문법에 맞지 않는 정규식으로 조건을 만들면, 한글 오류와 원인이 전달됩니다.") {
          val invalidPattern = "["

          val exception =
              shouldThrow<IllegalArgumentException> { MatchCondition(listOf(invalidPattern)) }

          exception.message shouldBe "정규식 패턴이 올바르지 않습니다."
          exception.cause?.javaClass shouldBe PatternSyntaxException::class.java
        }
      }

      feature("정규식 조건 불변성") {
        scenario("생성에 사용한 패턴 목록을 바꾸면, 조건은 처음 패턴을 유지합니다.") {
          val suppliedPatterns = mutableListOf("RepositoryImpl")
          val matchCondition = MatchCondition(suppliedPatterns)

          suppliedPatterns[0] = "OrderService"

          matchCondition.matches("class OrderRepositoryImpl") shouldBe true
        }

        scenario("공개된 패턴 목록을 바꾸면, 수정이 거부됩니다.") {
          val matchCondition = MatchCondition(listOf("RepositoryImpl"))

          shouldThrow<UnsupportedOperationException> {
            (matchCondition.patterns as MutableList<String>)[0] = "OrderService"
          }

          matchCondition.matches("class OrderRepositoryImpl") shouldBe true
        }
      }
    })

private data class MatchConditionSourceScenario(
    val name: String,
    val source: String,
    val expected: Boolean,
)

private data class PatternCountScenario(
    val name: String,
    val patterns: List<String>,
    val expectedCount: Int,
)

private data class BlankPatternScenario(
    val name: String,
    val pattern: String,
)
