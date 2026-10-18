package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class GuidelineSearchTest :
    FeatureSpec({
      feature("파일별 언어 적용") {
        scenario("언어가 다른 소스 코드가 있는 요청을 검색하면, 해당 언어 지침이 입력 순서로 반환됩니다.") {
          val repository = RepositoryIdentity("acme", "backend-order")
          val event = GuidelineEvent("file_changed")
          val kotlinGuideline = guideline("kotlin-rule", "kotlin", "RepositoryImpl")
          val javaGuideline = guideline("java-rule", "java", "RepositoryImpl")
          val sources =
              listOf(
                  SourceFile(
                      filepath = Path.of("/workspace/src/main/java/OrderRepository.java"),
                      content = "class OrderRepositoryImpl",
                      language = Language("java"),
                  ),
                  SourceFile(
                      filepath = Path.of("/workspace/src/main/kotlin/OrderRepository.kt"),
                      content = "class OrderRepositoryImpl",
                      language = Language("kotlin"),
                  ),
              )

          val matches =
              GuidelineSearch()
                  .search(
                      repository = repository,
                      event = event,
                      sources = sources,
                      guidelines = listOf(kotlinGuideline, javaGuideline),
                  )

          matches.map { it.id } shouldBe listOf(kotlinGuideline.id, javaGuideline.id)
          matches.map { it.content } shouldBe listOf("kotlin 지침 본문", "java 지침 본문")
        }
      }

      feature("여러 소스의 지침 검색") {
        scenario("여러 파일에서 관련 지침을 검색하면, 입력 지침 순서로 ID당 한 번 반환됩니다.") {
          val repository = RepositoryIdentity("acme", "backend-order")
          val event = GuidelineEvent("file_changed")
          val repositoryGuideline = guideline("repository-rule", "*", "Repository")
          val serviceGuideline = guideline("service-rule", "*", "Service")
          val sources =
              listOf(
                  SourceFile(
                      filepath = Path.of("/workspace/src/OrderRepository.kt"),
                      content = "class OrderRepositoryImpl",
                      language = Language("kotlin"),
                  ),
                  SourceFile(
                      filepath = Path.of("/workspace/src/OrderService.kt"),
                      content = "class OrderService",
                      language = Language("kotlin"),
                  ),
                  SourceFile(
                      filepath = Path.of("/workspace/src/AnotherRepository.kt"),
                      content = "class AnotherRepositoryImpl",
                      language = Language("kotlin"),
                  ),
              )

          val matches =
              GuidelineSearch()
                  .search(
                      repository = repository,
                      event = event,
                      sources = sources,
                      guidelines = listOf(serviceGuideline, repositoryGuideline),
                  )

          matches shouldBe
              listOf(
                  MatchedGuideline(serviceGuideline.id, serviceGuideline.content),
                  MatchedGuideline(repositoryGuideline.id, repositoryGuideline.content),
              )
        }
      }

      feature("조건별 지침 제외") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                SearchExclusionScenario(
                    name = "저장소 범위가 다르면 지침을 검색해도 결과에 포함되지 않습니다.",
                    repository = "other/backend-order",
                    language = "kotlin",
                    event = "file_changed",
                    pattern = "OrderRepository",
                ),
                SearchExclusionScenario(
                    name = "이벤트가 다르면 지침을 검색해도 결과에 포함되지 않습니다.",
                    repository = "*",
                    language = "kotlin",
                    event = "file_created",
                    pattern = "OrderRepository",
                ),
                SearchExclusionScenario(
                    name = "소스 언어가 다르면 지침을 검색해도 결과에 포함되지 않습니다.",
                    repository = "*",
                    language = "java",
                    event = "file_changed",
                    pattern = "OrderRepository",
                ),
                SearchExclusionScenario(
                    name = "정규식이 소스 코드에 없으면 지침을 검색해도 결과에 포함되지 않습니다.",
                    repository = "*",
                    language = "kotlin",
                    event = "file_changed",
                    pattern = "MissingType",
                ),
            ),
        ) { scenario ->
          val repository = RepositoryIdentity("acme", "backend-order")
          val event = GuidelineEvent("file_changed")
          val sources =
              listOf(
                  SourceFile(
                      filepath = Path.of("/workspace/src/OrderRepository.kt"),
                      content = "class OrderRepositoryImpl",
                      language = Language("kotlin"),
                  )
              )
          val guidelines =
              listOf(
                  guideline(
                      id = "excluded-rule",
                      language = scenario.language,
                      pattern = scenario.pattern,
                      repository = scenario.repository,
                      event = scenario.event,
                  )
              )
          val search = GuidelineSearch()

          val matches = search.search(repository, event, sources, guidelines)

          matches shouldBe emptyList()
        }
      }

      feature("반복 지침 검색") {
        scenario("같은 입력으로 지침을 반복 검색하면, 결과와 입력은 유지됩니다.") {
          val search = GuidelineSearch()
          val repository = RepositoryIdentity("acme", "backend-order")
          val event = GuidelineEvent("file_changed")
          val sources =
              listOf(
                  SourceFile(
                      filepath = Path.of("/workspace/src/OrderRepository.kt"),
                      content = "class OrderRepositoryImpl",
                      language = Language("kotlin"),
                  )
              )
          val guideline = guideline("repository-rule", "kotlin", "RepositoryImpl")
          val guidelines = listOf(guideline)

          val firstResult = search.search(repository, event, sources, guidelines)
          val secondResult = search.search(repository, event, sources, guidelines)

          firstResult shouldBe listOf(MatchedGuideline(guideline.id, guideline.content))
          secondResult shouldBe firstResult
          sources shouldBe
              listOf(
                  SourceFile(
                      filepath = Path.of("/workspace/src/OrderRepository.kt"),
                      content = "class OrderRepositoryImpl",
                      language = Language("kotlin"),
                  )
              )
          guideline.id shouldBe GuidelineId("repository-rule")
          guideline.scope.repositories shouldBe listOf("*")
          guideline.scope.languages shouldBe listOf("kotlin")
          guideline.events shouldBe setOf(GuidelineEvent("file_changed"))
          guideline.match.patterns shouldBe listOf("RepositoryImpl")
          guidelines.map { it.content } shouldBe listOf("kotlin 지침 본문")
        }
      }

      feature("관련 지침 없음") {
        scenario("지침 목록이 비어 있는 요청을 검색하면, 검색 결과도 비어 있습니다.") {
          val matches =
              GuidelineSearch()
                  .search(
                      repository = RepositoryIdentity("acme", "backend-order"),
                      event = GuidelineEvent("file_changed"),
                      sources =
                          listOf(
                              SourceFile(
                                  filepath = Path.of("/workspace/src/OrderService.kt"),
                                  content = "class OrderService",
                                  language = Language("kotlin"),
                              )
                          ),
                      guidelines = emptyList(),
                  )

          matches shouldBe emptyList()
        }
      }
    })

private fun guideline(
    id: String,
    language: String,
    pattern: String,
    repository: String = "*",
    event: String = "file_changed",
): Guideline =
    Guideline(
        id = GuidelineId(id),
        scope = Scope(repositories = listOf(repository), languages = listOf(language)),
        events = setOf(GuidelineEvent(event)),
        match = MatchCondition(listOf(pattern)),
        content = "$language 지침 본문",
    )

private data class SearchExclusionScenario(
    val name: String,
    val repository: String,
    val language: String,
    val event: String,
    val pattern: String,
)
