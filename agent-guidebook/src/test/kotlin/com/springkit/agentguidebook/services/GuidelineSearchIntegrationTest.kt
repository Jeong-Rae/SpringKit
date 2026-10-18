package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.client.GuidelineSearchFactory
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.errors.SourceReadException
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GuidelineSearchIntegrationTest :
    FeatureSpec({
      feature("지침 검색 결과") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                KotlinExtensionScenario(
                    name = ".kt 파일을 검색하면, 지침 ID와 한글 본문이 반환됩니다.",
                    extension = "kt",
                ),
                KotlinExtensionScenario(
                    name = ".kts 파일을 검색하면, 지침 ID와 한글 본문이 반환됩니다.",
                    extension = "kts",
                ),
            ),
        ) { scenario ->
          withProject { cwd ->
            writeGuideline(
                cwd,
                "orders/guideline.yaml",
                guidelineYaml(
                    id = "주문-저장소-규칙",
                    patterns = listOf("OrderRepositoryImpl"),
                    content = "서비스는 저장소 추상화에 의존해야 합니다.",
                ),
            )
            val filepath =
                writeSource(
                    cwd,
                    "src/main/kotlin/OrderRepository.${scenario.extension}",
                    "class OrderRepositoryImpl",
                )

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches shouldBe
                listOf(
                    MatchedGuideline(
                        id = GuidelineId("주문-저장소-규칙"),
                        content = "서비스는 저장소 추상화에 의존해야 합니다.\n",
                    )
                )
          }
        }

        scenario("여러 파일과 지침을 검색하면, 정렬된 지침 경로 순서로 ID당 한 번 반환됩니다.") {
          withProject { cwd ->
            writeGuideline(
                cwd,
                "zeta/guideline.yaml",
                guidelineYaml(id = "zeta-rule", patterns = listOf("OrderService")),
            )
            writeGuideline(
                cwd,
                "alpha/guideline.yaml",
                guidelineYaml(id = "alpha-rule", patterns = listOf("RepositoryImpl")),
            )
            val serviceFile = writeSource(cwd, "src/OrderService.kt", "class OrderService")
            val repositoryFile =
                writeSource(cwd, "src/OrderRepository.kt", "class OrderRepositoryImpl")
            val repeatedRepositoryFile =
                writeSource(cwd, "src/AnotherRepository.kt", "class AnotherRepositoryImpl")
            val normalizedRepositoryFile = cwd.resolve("src/../src/OrderRepository.kt")

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(
                        GuidelineSearchRequest(
                            cwd = cwd,
                            filepaths =
                                listOf(
                                    serviceFile,
                                    repositoryFile,
                                    repeatedRepositoryFile,
                                    normalizedRepositoryFile,
                                ),
                        )
                    )

            matches.map { it.id } shouldBe
                listOf(GuidelineId("alpha-rule"), GuidelineId("zeta-rule"))
          }
        }

        scenario("중첩 지침 파일을 검색하면, guideline.yaml만 상대 경로 순서로 반환됩니다.") {
          withProject { cwd ->
            writeGuideline(
                cwd,
                "zeta/rules/guideline.yaml",
                guidelineYaml(id = "zeta-rule", patterns = listOf("OrderService")),
            )
            writeGuideline(
                cwd,
                "alpha/nested/guideline.yaml",
                guidelineYaml(id = "alpha-rule", patterns = listOf("OrderService")),
            )
            writeGuideline(cwd, "middle/ignored.yaml", "잘못된 YAML: [\n")
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches.map { it.id } shouldBe
                listOf(GuidelineId("alpha-rule"), GuidelineId("zeta-rule"))
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                OriginNormalizationScenario(
                    name = "콜론을 쓰는 SSH origin으로 지침을 검색하면, 저장소 범위가 일치합니다.",
                    origin = "git@host:acme/backend-order.git",
                ),
                OriginNormalizationScenario(
                    name = "SSH URL 형식 origin으로 지침을 검색하면, 저장소 범위가 일치합니다.",
                    origin = "ssh://git@host/acme/backend-order.git",
                ),
                OriginNormalizationScenario(
                    name = "HTTPS origin으로 지침을 검색하면, 저장소 범위가 일치합니다.",
                    origin = "https://host/acme/backend-order.git",
                ),
            ),
        ) { scenario ->
          withProject(origin = scenario.origin) { cwd ->
            writeGuideline(
                cwd,
                "repository/guideline.yaml",
                guidelineYaml(
                    id = "repository-rule",
                    repositories = listOf("acme/backend-order"),
                    patterns = listOf("OrderService"),
                ),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches.map { it.id } shouldBe listOf(GuidelineId("repository-rule"))
          }
        }

        scenario("현재 19개 지침을 복사하고 추상화 정규식에 맞는 코드를 검색하면, 해당 ID와 본문을 반환합니다.") {
          withProject { cwd ->
            val copiedGuidelineCount = copyProjectGuidelines(cwd)
            val filepath =
                writeSource(cwd, "src/main/kotlin/OrderRepository.kt", "interface OrderRepository")

            copiedGuidelineCount shouldBe 19

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches.map { it.id } shouldBe
                listOf(GuidelineId("abstract-dependency-boundaries-and-confirmed-extension-points"))
            matches.single().content shouldContain "추상화는 의존성 방향을 분리"
          }
        }
      }

      feature("변경된 소스 재검색") {
        scenario("같은 서비스로 변경된 소스를 다시 검색하면, 현재 내용에 맞는 지침을 반환합니다.") {
          withProject { cwd ->
            writeGuideline(
                cwd,
                "orders/guideline.yaml",
                guidelineYaml(
                    id = "service-rule",
                    patterns = listOf("OrderService"),
                    content = "서비스 지침",
                ),
            )
            writeGuideline(
                cwd,
                "repositories/guideline.yaml",
                guidelineYaml(
                    id = "repository-rule",
                    patterns = listOf("OrderRepository"),
                    content = "저장소 지침",
                ),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")
            val service = GuidelineSearchFactory.fileSystem()
            val request = GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath))

            val initialMatches = service.search(request)
            Files.writeString(filepath, "class OrderRepository")
            val updatedMatches = service.search(request)

            initialMatches.map { it.id } shouldBe listOf(GuidelineId("service-rule"))
            updatedMatches.map { it.id } shouldBe listOf(GuidelineId("repository-rule"))
          }
        }
      }

      feature("검색 입력 보존") {
        scenario("소스와 YAML을 검색하면, 검색 전후 바이트가 유지됩니다.") {
          withProject { cwd ->
            val guidelinePath = cwd.resolve(".agents/guides/orders/guideline.yaml")
            writeGuideline(
                cwd,
                "orders/guideline.yaml",
                guidelineYaml(
                    id = "service-rule",
                    patterns = listOf("OrderService"),
                    content = "서비스 지침입니다.",
                ),
            )
            val filepath =
                writeSource(cwd, "src/OrderService.kt", "class OrderService // 변경하지 않습니다")
            val sourceBytesBefore = Files.readAllBytes(filepath).toList()
            val guidelineBytesBefore = Files.readAllBytes(guidelinePath).toList()

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches.map { it.id } shouldBe listOf(GuidelineId("service-rule"))
            Files.readAllBytes(filepath).toList() shouldBe sourceBytesBefore
            Files.readAllBytes(guidelinePath).toList() shouldBe guidelineBytesBefore
          }
        }
      }

      feature("검색 조건 제외") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                SearchMismatchScenario(
                    name = "저장소 범위가 다른 지침을 검색하면, 결과에 포함되지 않습니다.",
                    repositories = listOf("other/backend-order"),
                    languages = listOf("kotlin"),
                    event = "file_changed",
                    patterns = listOf("OrderService"),
                ),
                SearchMismatchScenario(
                    name = "언어 범위가 다른 지침을 검색하면, 결과에 포함되지 않습니다.",
                    repositories = listOf("*"),
                    languages = listOf("java"),
                    event = "file_changed",
                    patterns = listOf("OrderService"),
                ),
                SearchMismatchScenario(
                    name = "이벤트가 다른 지침을 검색하면, 결과에 포함되지 않습니다.",
                    repositories = listOf("*"),
                    languages = listOf("kotlin"),
                    event = "file_created",
                    patterns = listOf("OrderService"),
                ),
                SearchMismatchScenario(
                    name = "정규식이 소스 코드에 없는 지침을 검색하면, 결과에 포함되지 않습니다.",
                    repositories = listOf("*"),
                    languages = listOf("kotlin"),
                    event = "file_changed",
                    patterns = listOf("OrderRepository"),
                ),
                SearchMismatchScenario(
                    name = "정규식 목록이 빈 지침을 검색하면, 결과에 포함되지 않습니다.",
                    repositories = listOf("*"),
                    languages = listOf("kotlin"),
                    event = "file_changed",
                    patterns = emptyList(),
                ),
            ),
        ) { scenario ->
          withProject { cwd ->
            writeGuideline(
                cwd,
                "rules/guideline.yaml",
                guidelineYaml(
                    id = "excluded-rule",
                    repositories = scenario.repositories,
                    languages = scenario.languages,
                    event = scenario.event,
                    patterns = scenario.patterns,
                ),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))

            matches shouldBe emptyList()
          }
        }
      }

      feature("빈 검색 결과") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                EmptySearchScenario(
                    name = "파일 경로가 비어 있는 요청을 검색하면, 저장소 origin 없이 빈 결과를 반환합니다.",
                    filepath = null,
                    createGuideline = false,
                    createGuideDirectory = false,
                    origin = null,
                ),
                EmptySearchScenario(
                    name = "Kotlin 파일이 아닌 경로만 검색하면, 빈 결과를 반환합니다.",
                    filepath = "src/OrderService.java",
                    createGuideline = true,
                    createGuideDirectory = true,
                    origin = null,
                ),
                EmptySearchScenario(
                    name = "지침 디렉터리가 없는 프로젝트에서 Kotlin 파일을 검색하면, 빈 결과를 반환합니다.",
                    filepath = "src/OrderService.kt",
                    createGuideline = false,
                    createGuideDirectory = false,
                    origin = null,
                ),
                EmptySearchScenario(
                    name = "지침 후보가 없는 프로젝트에서 Kotlin 파일을 검색하면, 저장소 origin 없이 빈 결과를 반환합니다.",
                    filepath = "src/OrderService.kt",
                    createGuideline = false,
                    createGuideDirectory = true,
                    origin = null,
                ),
            ),
        ) { scenario ->
          withProject(origin = scenario.origin) { cwd ->
            val filepath = scenario.filepath?.let { writeSource(cwd, it, "class OrderService") }
            if (scenario.createGuideline) {
              writeGuideline(
                  cwd,
                  "rules/guideline.yaml",
                  guidelineYaml(id = "service-rule", patterns = listOf("OrderService")),
              )
            }
            if (scenario.createGuideDirectory) {
              Files.createDirectories(cwd.resolve(".agents/guides"))
            }

            val matches =
                GuidelineSearchFactory.fileSystem()
                    .search(
                        GuidelineSearchRequest(
                            cwd = cwd,
                            filepaths = filepath?.let(::listOf) ?: emptyList(),
                        )
                    )

            matches shouldBe emptyList()
          }
        }
      }

      feature("검색 요청 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidCwdScenario(
                    name = "현재 경로가 상대 경로인 요청을 검색하면, 요청이 거부됩니다.",
                    kind = "relative",
                ),
                InvalidCwdScenario(
                    name = "현재 경로가 없는 요청을 검색하면, 요청이 거부됩니다.",
                    kind = "missing",
                ),
                InvalidCwdScenario(
                    name = "현재 경로가 파일인 요청을 검색하면, 요청이 거부됩니다.",
                    kind = "file",
                ),
            ),
        ) { scenario ->
          withProject { cwd ->
            val invalidCwd =
                when (scenario.kind) {
                  "relative" -> Path.of("relative/project")
                  "missing" -> cwd.resolve("missing-project")
                  "file" -> writeSource(cwd, "project-file", "내용")
                  else -> error("테스트 입력 종류가 올바르지 않습니다.")
                }
            val filepath = cwd.resolve("src/OrderService.kt")

            shouldThrow<InvalidSearchRequestException> {
              GuidelineSearchFactory.fileSystem()
                  .search(GuidelineSearchRequest(cwd = invalidCwd, filepaths = listOf(filepath)))
            }
          }
        }

        scenario("파일 경로가 상대 경로인 요청을 검색하면, 요청이 거부됩니다.") {
          withProject { cwd ->
            shouldThrow<InvalidSearchRequestException> {
              GuidelineSearchFactory.fileSystem()
                  .search(
                      GuidelineSearchRequest(
                          cwd = cwd,
                          filepaths = listOf(Path.of("src/OrderService.kt")),
                      )
                  )
            }
          }
        }
      }

      feature("소스 파일 읽기 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                SourceReadScenario(
                    name = "Kotlin 파일이 없는 경로를 검색하면, 소스 읽기가 거부됩니다.",
                    targetKind = "missing",
                ),
                SourceReadScenario(
                    name = "Kotlin 파일 경로가 디렉터리일 때 검색하면, 소스 읽기가 거부됩니다.",
                    targetKind = "directory",
                ),
            ),
        ) { scenario ->
          withProject { cwd ->
            writeGuideline(
                cwd,
                "rules/guideline.yaml",
                guidelineYaml(id = "service-rule", patterns = listOf("OrderService")),
            )
            val filepath =
                when (scenario.targetKind) {
                  "missing" -> cwd.resolve("src/Missing.kt")
                  "directory" -> cwd.resolve("src/OrderService.kt").also(Files::createDirectories)
                  else -> error("테스트 입력 종류가 올바르지 않습니다.")
                }

            shouldThrow<SourceReadException> {
              GuidelineSearchFactory.fileSystem()
                  .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
            }
          }
        }

        scenario("잘못된 UTF-8이 포함된 Kotlin 파일을 검색하면, 소스 읽기 오류가 디코딩 원인을 보존합니다.") {
          withProject { cwd ->
            writeGuideline(
                cwd,
                "rules/guideline.yaml",
                guidelineYaml(id = "service-rule", patterns = listOf("OrderService")),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "")
            Files.write(filepath, byteArrayOf(0xC3.toByte(), 0x28))

            val exception =
                shouldThrow<SourceReadException> {
                  GuidelineSearchFactory.fileSystem()
                      .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
                }

            (exception.cause is java.nio.charset.CharacterCodingException) shouldBe true
          }
        }
      }

      feature("지침 로딩 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidGuidelineScenario(
                    name = "YAML 문법이 잘못된 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml = "id: [\n",
                ),
                InvalidGuidelineScenario(
                    name = "필수 언어 범위가 없는 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml =
                        """
                        id: incomplete-rule
                        scope:
                          repositories: ["*"]
                        when: [file_changed]
                        match:
                          any: []
                        content: |
                          본문
                        """
                            .trimIndent(),
                ),
                InvalidGuidelineScenario(
                    name = "저장소 범위 타입이 목록이 아닌 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml =
                        """
                        id: wrong-type-rule
                        scope:
                          repositories: "*"
                          languages: [kotlin]
                        when: [file_changed]
                        match:
                          any: []
                        content: |
                          본문
                        """
                            .trimIndent(),
                ),
                InvalidGuidelineScenario(
                    name = "YAML 키가 중복된 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml =
                        """
                        id: first-rule
                        id: second-rule
                        scope:
                          repositories: ["*"]
                          languages: [kotlin]
                        when: [file_changed]
                        match:
                          any: []
                        content: |
                          본문
                        """
                            .trimIndent(),
                ),
                InvalidGuidelineScenario(
                    name = "정규식 문법이 잘못된 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml = guidelineYaml(id = "invalid-regex-rule", patterns = listOf("[")),
                ),
                InvalidGuidelineScenario(
                    name = "정규식이 2개 이상인 지침을 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml =
                        guidelineYaml(id = "multiple-regex-rule", patterns = listOf("one", "two")),
                ),
                InvalidGuidelineScenario(
                    name = "지침 파일의 ID가 중복된 프로젝트를 검색하면, 지침 로딩이 거부됩니다.",
                    firstYaml = guidelineYaml(id = "duplicate-rule"),
                    secondYaml =
                        guidelineYaml(id = "duplicate-rule", patterns = listOf("Repository")),
                ),
            ),
        ) { scenario ->
          withProject { cwd ->
            writeGuideline(cwd, "alpha/guideline.yaml", scenario.firstYaml)
            scenario.secondYaml?.let { writeGuideline(cwd, "zeta/guideline.yaml", it) }
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            shouldThrow<GuidelineLoadException> {
              GuidelineSearchFactory.fileSystem()
                  .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
            }
          }
        }

        scenario("지침 경로가 파일인 프로젝트를 검색하면, 지침 로딩이 거부됩니다.") {
          withProject { cwd ->
            writeSource(cwd, ".agents/guides", "디렉터리 대신 파일")
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            shouldThrow<GuidelineLoadException> {
              GuidelineSearchFactory.fileSystem()
                  .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
            }
          }
        }

        scenario("저장소 범위에 공백 항목이 있는 지침을 검색하면, 생성 오류 원인을 보존합니다.") {
          withProject { cwd ->
            writeGuideline(
                cwd,
                "rules/guideline.yaml",
                guidelineYaml(id = "blank-repository-rule", repositories = listOf(" ")),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            val exception =
                shouldThrow<GuidelineLoadException> {
                  GuidelineSearchFactory.fileSystem()
                      .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
                }

            (exception.cause is IllegalArgumentException) shouldBe true
          }
        }
      }

      feature("저장소 식별 거부") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                OriginScenario(
                    name = "origin이 없는 프로젝트에서 지침을 검색하면, 저장소 식별이 거부됩니다.",
                    origin = null,
                ),
                OriginScenario(
                    name = "origin을 정규화할 수 없는 프로젝트에서 지침을 검색하면, 저장소 식별이 거부됩니다.",
                    origin = "not-a-repository-url",
                ),
            ),
        ) { scenario ->
          withProject(origin = scenario.origin) { cwd ->
            writeGuideline(
                cwd,
                "rules/guideline.yaml",
                guidelineYaml(id = "service-rule", patterns = listOf("OrderService")),
            )
            val filepath = writeSource(cwd, "src/OrderService.kt", "class OrderService")

            shouldThrow<RepositoryIdentityUnavailableException> {
              GuidelineSearchFactory.fileSystem()
                  .search(GuidelineSearchRequest(cwd = cwd, filepaths = listOf(filepath)))
            }
          }
        }
      }
    })

private data class SearchMismatchScenario(
    val name: String,
    val repositories: List<String>,
    val languages: List<String>,
    val event: String,
    val patterns: List<String>,
)

private data class KotlinExtensionScenario(
    val name: String,
    val extension: String,
)

private data class EmptySearchScenario(
    val name: String,
    val filepath: String?,
    val createGuideline: Boolean,
    val createGuideDirectory: Boolean,
    val origin: String?,
)

private data class InvalidCwdScenario(
    val name: String,
    val kind: String,
)

private data class SourceReadScenario(
    val name: String,
    val targetKind: String,
)

private data class InvalidGuidelineScenario(
    val name: String,
    val firstYaml: String,
    val secondYaml: String? = null,
)

private data class OriginNormalizationScenario(
    val name: String,
    val origin: String,
)

private data class OriginScenario(
    val name: String,
    val origin: String?,
)

private fun withProject(
    origin: String? = "git@host:acme/backend-order.git",
    action: (Path) -> Unit,
) {
  val cwd = Files.createTempDirectory(Path.of("/tmp"), "guidebook-search-")

  try {
    runGit(cwd, "init", "--quiet")
    origin?.let { runGit(cwd, "remote", "add", "origin", it) }
    action(cwd)
  } finally {
    deleteTree(cwd)
  }
}

private fun runGit(cwd: Path, vararg arguments: String) {
  val process =
      ProcessBuilder(listOf("git", "-C", cwd.toString()) + arguments)
          .redirectErrorStream(true)
          .start()
  val output = process.inputStream.bufferedReader().use { it.readText() }
  val exitCode = process.waitFor()

  check(exitCode == 0) { "테스트용 Git 명령이 실패했습니다: $output" }
}

private fun writeSource(cwd: Path, relativePath: String, content: String): Path {
  val filepath = cwd.resolve(relativePath)
  Files.createDirectories(filepath.parent)
  Files.writeString(filepath, content, StandardCharsets.UTF_8)
  return filepath
}

private fun writeGuideline(cwd: Path, relativePath: String, content: String) {
  writeSource(cwd, ".agents/guides/$relativePath", content)
}

private fun guidelineYaml(
    id: String,
    repositories: List<String> = listOf("*"),
    languages: List<String> = listOf("kotlin"),
    event: String = "file_changed",
    patterns: List<String> = listOf("OrderService"),
    content: String = "서비스 규칙입니다.",
): String {
  return buildString {
    append("id: '${yamlSingleQuoted(id)}'\n")
    append("scope:\n")
    append("  repositories:")
    appendYamlValues(repositories)
    append("  languages:")
    appendYamlValues(languages)
    append("when:\n")
    append("  - '${yamlSingleQuoted(event)}'\n")
    append("match:\n")
    if (patterns.isEmpty()) {
      append("  any: []\n")
    } else {
      append("  any:\n")
      patterns.forEach { pattern ->
        append("    - regex:\n")
        append("        pattern: '${yamlSingleQuoted(pattern)}'\n")
      }
    }
    append("content: |\n")
    content.lines().forEach { line ->
      append("  ")
      append(line)
      append('\n')
    }
  }
}

private fun StringBuilder.appendYamlValues(values: List<String>) {
  if (values.isEmpty()) {
    append(" []\n")
    return
  }

  append('\n')
  values.forEach { value ->
    append("    - '${yamlSingleQuoted(value)}'\n")
  }
}

private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")

private fun copyProjectGuidelines(cwd: Path): Int {
  val guidebookRoot = projectGuidelinesRoot()
  val guidelineFiles =
      Files.walk(guidebookRoot).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.fileName.toString() == "guideline.yaml" }
            .sorted()
            .toList()
      }

  guidelineFiles.forEach { sourceFile ->
    val relativePath = guidebookRoot.relativize(sourceFile)
    val destination = cwd.resolve(".agents/guides").resolve(relativePath)
    Files.createDirectories(destination.parent)
    Files.copy(sourceFile, destination)
  }

  return guidelineFiles.size
}

private fun projectGuidelinesRoot(): Path {
  val currentDirectory = Path.of("").toAbsolutePath()
  val root =
      generateSequence(currentDirectory) { it.parent }
          .map { it.resolve(".agents/guides") }
          .firstOrNull(Files::isDirectory)

  return requireNotNull(root) { "프로젝트 지침 디렉터리를 찾을 수 없습니다." }
}

private fun deleteTree(root: Path) {
  if (!Files.exists(root)) {
    return
  }

  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
  }
}
