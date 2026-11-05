package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.Guideline
import com.springkit.agentguidebook.domain.GuidelineEvent
import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.domain.Language
import com.springkit.agentguidebook.domain.MatchCondition
import com.springkit.agentguidebook.domain.MatchedGuideline
import com.springkit.agentguidebook.domain.RepositoryIdentity
import com.springkit.agentguidebook.domain.Scope
import com.springkit.agentguidebook.domain.SourceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class SearchGuidelinesUseCaseTest :
    FeatureSpec({
      feature("인메모리 입력을 통한 지침 검색") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                ApplicationSearchScenario(
                    name = "가상 프로젝트에서 정규식에 맞는 소스를 검색하면, 지침 ID와 한글 본문이 반환됩니다.",
                    sourceContent = "class OrderService",
                    expected =
                        listOf(
                            MatchedGuideline(
                                id = GuidelineId("service-rule"),
                                content = "서비스는 저장소 추상화에 의존해야 합니다.",
                            )
                        ),
                ),
                ApplicationSearchScenario(
                    name = "가상 프로젝트에서 정규식에 맞지 않는 소스를 검색하면, 빈 결과를 반환합니다.",
                    sourceContent = "class UnrelatedType",
                    expected = emptyList(),
                ),
            ),
        ) { scenario ->
          val cwd = Path.of("/virtual/project")
          val sourcePath = cwd.resolve("src/OrderService.kt")
          val source =
              SourceFile(
                  filepath = sourcePath,
                  content = scenario.sourceContent,
                  language = Language("kotlin"),
              )
          val guideline =
              Guideline(
                  id = GuidelineId("service-rule"),
                  scope = Scope(repositories = listOf("*"), languages = listOf("kotlin")),
                  events = listOf(GuidelineEvent("file_changed")),
                  match = MatchCondition(listOf("OrderService")),
                  content = "서비스는 저장소 추상화에 의존해야 합니다.",
              )
          val inputReader =
              InMemoryInputReader(
                  sources = listOf(source),
                  guidelines = listOf(guideline),
                  repositoryIdentity = RepositoryIdentity("acme", "backend-order"),
              )
          val useCase = SearchGuidelinesUseCase(inputReader)
          val request =
              SearchRequest(
                  cwd = cwd,
                  filepaths = listOf(cwd.resolve("src/../src/OrderService.kt")),
              )

          val matches = useCase.execute(request)

          matches shouldBe scenario.expected
        }
      }

      feature("빈 검색 결과") {
        scenario("파일 경로가 비어 있고 지침 읽기가 불필요하면, 저장소 origin 없이 빈 결과를 반환합니다.") {
          val cwd = Path.of("/virtual/project")
          val dataReader =
              InMemoryInputReader(
                  sources = emptyList(),
                  guidelines = emptyList(),
                  repositoryIdentity = null,
              )
          val inputReader =
              object : InputReader by dataReader {
                override fun readGuidelines(root: Path): List<Guideline> =
                    throw GuidelineLoadException("소스가 없으면 지침을 읽지 않습니다.")
              }
          val useCase = SearchGuidelinesUseCase(inputReader)
          val request = SearchRequest(cwd = cwd, filepaths = emptyList())

          val matches = useCase.execute(request)

          matches shouldBe emptyList()
        }

        scenario("지침 후보가 없으면, 저장소 origin 없이 빈 결과를 반환합니다.") {
          val cwd = Path.of("/virtual/project")
          val sourcePath = cwd.resolve("src/OrderService.kt")
          val source =
              SourceFile(
                  filepath = sourcePath,
                  content = "class OrderService",
                  language = Language("kotlin"),
              )
          val inputReader =
              InMemoryInputReader(
                  sources = listOf(source),
                  guidelines = emptyList(),
                  repositoryIdentity = null,
              )
          val useCase = SearchGuidelinesUseCase(inputReader)
          val request = SearchRequest(cwd = cwd, filepaths = listOf(sourcePath))

          val matches = useCase.execute(request)

          matches shouldBe emptyList()
        }
      }

      feature("소스 읽기 오류 전달") {
        scenario("읽기 경계에서 대상 소스가 없으면, 소스 읽기 오류가 전달됩니다.") {
          val cwd = Path.of("/virtual/project")
          val missingSourcePath = cwd.resolve("src/Missing.kt")
          val dataReader =
              InMemoryInputReader(
                  sources = emptyList(),
                  guidelines = emptyList(),
                  repositoryIdentity = RepositoryIdentity("acme", "backend-order"),
              )
          val inputReader =
              object : InputReader by dataReader {
                override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
                    throw SourceReadException("인메모리 소스가 없습니다: $missingSourcePath")
              }
          val useCase = SearchGuidelinesUseCase(inputReader)
          val request = SearchRequest(cwd = cwd, filepaths = listOf(missingSourcePath))

          shouldThrow<SourceReadException> { useCase.execute(request) }
        }

        scenario("읽기 경계에서 소스 원인이 있는 오류를 받으면, 원인이 보존됩니다.") {
          val cwd = Path.of("/virtual/project")
          val sourcePath = cwd.resolve("src/OrderService.kt")
          val cause = IllegalStateException("인메모리 소스 읽기 실패")
          val failure = SourceReadException("소스를 읽을 수 없습니다.", cause)
          val dataReader =
              InMemoryInputReader(
                  sources = emptyList(),
                  guidelines = emptyList(),
                  repositoryIdentity = RepositoryIdentity("acme", "backend-order"),
              )
          val inputReader =
              object : InputReader by dataReader {
                override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
                    throw failure
              }
          val useCase = SearchGuidelinesUseCase(inputReader)
          val request = SearchRequest(cwd = cwd, filepaths = listOf(sourcePath))

          val exception = shouldThrow<SourceReadException> { useCase.execute(request) }

          exception.cause shouldBe cause
        }
      }

      feature("검색 요청 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidRequestScenario(
                    name = "현재 경로가 상대 경로이면, 읽기 경계를 부르기 전에 요청이 거부됩니다.",
                    cwd = Path.of("relative/project"),
                    filepath = Path.of("/virtual/project/src/Order.kt"),
                    expectedMessage = "프로젝트 경로",
                ),
                InvalidRequestScenario(
                    name = "파일 경로가 상대 경로이면, 읽기 경계를 부르기 전에 요청이 거부됩니다.",
                    cwd = Path.of("/virtual/project"),
                    filepath = Path.of("src/Order.kt"),
                    expectedMessage = "파일 경로",
                ),
            ),
        ) { scenario ->
          val useCase = SearchGuidelinesUseCase(UnexpectedInputReader())
          val request =
              SearchRequest(
                  cwd = scenario.cwd,
                  filepaths = listOf(scenario.filepath),
              )

          val exception = shouldThrow<InvalidSearchRequestException> { useCase.execute(request) }

          exception.message.orEmpty() shouldContain scenario.expectedMessage
        }
      }
    })

private data class ApplicationSearchScenario(
    val name: String,
    val sourceContent: String,
    val expected: List<MatchedGuideline>,
)

private data class InvalidRequestScenario(
    val name: String,
    val cwd: Path,
    val filepath: Path,
    val expectedMessage: String,
)

private class InMemoryInputReader(
    private val sources: List<SourceFile>,
    private val guidelines: List<Guideline>,
    private val repositoryIdentity: RepositoryIdentity?,
) : InputReader {
  override fun validateWorkingDirectory(cwd: Path) = Unit

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      sources.filter { source ->
        source.filepath in filepaths
      }

  override fun readGuidelines(root: Path): List<Guideline> = guidelines

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity =
      repositoryIdentity ?: throw RepositoryIdentityUnavailableException("인메모리에 저장소 origin이 없습니다.")
}

private class UnexpectedInputReader : InputReader {
  override fun validateWorkingDirectory(cwd: Path): Unit = unexpectedRead()

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> = unexpectedRead()

  override fun readGuidelines(root: Path): List<Guideline> = unexpectedRead()

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity = unexpectedRead()

  private fun unexpectedRead(): Nothing = error("읽기 경계를 호출하지 않아야 합니다.")
}
