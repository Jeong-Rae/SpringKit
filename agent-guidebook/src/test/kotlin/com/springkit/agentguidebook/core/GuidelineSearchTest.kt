package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import io.kotest.core.spec.style.FeatureSpec
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
): Guideline =
    Guideline(
        id = GuidelineId(id),
        scope = Scope(repositories = listOf("*"), languages = listOf(language)),
        events = setOf(GuidelineEvent("file_changed")),
        match = MatchCondition(listOf(pattern)),
        content = "$language 지침 본문",
    )
