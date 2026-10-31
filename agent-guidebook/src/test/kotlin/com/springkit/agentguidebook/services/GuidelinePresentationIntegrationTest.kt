package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.client.GuidelinePresentationFactory
import com.springkit.agentguidebook.client.GuidelineSearchFactory
import com.springkit.agentguidebook.config.SqlitePresentationConfig
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.PresentationDatabaseCorruptException
import com.springkit.agentguidebook.errors.PresentationStorageException
import com.springkit.agentguidebook.errors.PresentationStorageUnavailableException
import com.springkit.agentguidebook.errors.PresentationTransactionRollbackException
import com.springkit.agentguidebook.errors.RetryablePresentationStorageException
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.storage.SqlitePresentationRepository
import com.springkit.agentguidebook.storage.SqlitePresentationUnitOfWork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.TimeUnit

class GuidelinePresentationIntegrationTest :
    FeatureSpec({
      feature("실제 SQLite에 Presentation 이력 저장") {
        scenario("처음 검색한 지침을 경로 순서로 반환하고 UTC 시각과 기본 키를 저장합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "zeta/guideline.yaml",
                id = "zeta-rule",
                pattern = "OrderService",
                content = "뒤쪽 경로의 지침입니다.",
            )
            writeGuideline(
                project.cwd,
                "alpha/guideline.yaml",
                id = "alpha-rule",
                pattern = "OrderService",
                content = "앞쪽 경로의 지침입니다.",
            )
            val databasePath = project.cwd.resolve("history.sqlite")

            val result =
                presentationService(project.cwd, databasePath)
                    .present(request(project, "session-a"))

            result.map { it.id.value } shouldContainExactly listOf("alpha-rule", "zeta-rule")
            result.map { it.content } shouldContainExactly
                listOf("앞쪽 경로의 지침입니다.\n", "뒤쪽 경로의 지침입니다.\n")
            rows(databasePath, "session-a") shouldBe
                listOf(
                    StoredPresentation("session-a", "alpha-rule", FIXED_TIMESTAMP),
                    StoredPresentation("session-a", "zeta-rule", FIXED_TIMESTAMP),
                )
            presentationSchema(databasePath) shouldBe
                listOf(
                    SchemaColumn("session_id", 1),
                    SchemaColumn("guideline_id", 2),
                    SchemaColumn("presented_at", 0),
                )
          }
        }

        scenario("서비스를 다시 생성해 같은 Session을 검색하면 이전 ID를 제외합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            val firstService = presentationService(project.cwd, databasePath)
            val firstResult = firstService.present(request(project, "session-a"))
            val restartedService = presentationService(project.cwd, databasePath)

            val afterRestart = restartedService.present(request(project, "session-a"))

            firstResult.map { it.id.value } shouldBe listOf("service-rule")
            afterRestart shouldBe emptyList()
            rows(databasePath, "session-a") shouldHaveSize 1
          }
        }

        scenario("기존 일부 이력은 과거 시각을 보존하고 새 Session에는 독립적으로 제공합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "alpha/guideline.yaml",
                id = "alpha-rule",
                pattern = "OrderService",
            )
            writeGuideline(
                project.cwd,
                "zeta/guideline.yaml",
                id = "zeta-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            insertPresentation(databasePath, "session-a", "alpha-rule", OLD_TIMESTAMP)
            val service = presentationService(project.cwd, databasePath)

            val additionalForFirst = service.present(request(project, "session-a"))
            val firstForSecond = service.present(request(project, "session-b"))

            additionalForFirst.map { it.id.value } shouldBe listOf("zeta-rule")
            firstForSecond.map { it.id.value } shouldBe listOf("alpha-rule", "zeta-rule")
            rows(databasePath, "session-a") shouldBe
                listOf(
                    StoredPresentation("session-a", "alpha-rule", OLD_TIMESTAMP),
                    StoredPresentation("session-a", "zeta-rule", FIXED_TIMESTAMP),
                )
            rows(databasePath, "session-b") shouldBe
                listOf(
                    StoredPresentation("session-b", "alpha-rule", FIXED_TIMESTAMP),
                    StoredPresentation("session-b", "zeta-rule", FIXED_TIMESTAMP),
                )
          }
        }

        scenario("본문이 바뀐 기존 Guideline ID는 다시 제공하지 않습니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "stable-rule",
                pattern = "OrderService",
                content = "처음 본문입니다.",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            val service = presentationService(project.cwd, databasePath)
            service.present(request(project, "session-a"))
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "stable-rule",
                pattern = "OrderService",
                content = "수정된 본문입니다.",
            )

            val repeated = service.present(request(project, "session-a"))

            repeated shouldBe emptyList()
            rows(databasePath, "session-a") shouldBe
                listOf(StoredPresentation("session-a", "stable-rule", FIXED_TIMESTAMP))
          }
        }

        scenario("같은 파일을 수정한 뒤 다시 검색하면 현재 코드에 맞는 새 Guideline을 제공합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "service/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            writeGuideline(
                project.cwd,
                "repository/guideline.yaml",
                id = "repository-rule",
                pattern = "OrderRepository",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            val service = presentationService(project.cwd, databasePath)
            val first = service.present(request(project, "session-a"))
            Files.writeString(project.sourceFile, "class OrderRepository")

            val afterFileChange = service.present(request(project, "session-a"))

            first.map { it.id.value } shouldBe listOf("service-rule")
            afterFileChange.map { it.id.value } shouldBe listOf("repository-rule")
            rows(databasePath, "session-a").map { it.guidelineId } shouldBe
                listOf("repository-rule", "service-rule")
          }
        }

        scenario("관련 Kotlin 소스가 없으면 빈 결과를 반환하고 DB 파일을 만들지 않습니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("unused/history.sqlite")
            val service = presentationService(project.cwd, databasePath)
            val nonKotlinRequest =
                GuidelinePresentationRequest(
                    sessionId = SessionId("session-a"),
                    cwd = project.cwd,
                    filepaths = listOf(project.cwd.resolve("src/OrderService.java")),
                )

            val result = service.present(nonKotlinRequest)

            result shouldBe emptyList()
            Files.exists(databasePath) shouldBe false
          }
        }
      }

      feature("SQLite Presentation 오류와 원자성") {
        scenario("잘못된 검색 경로는 기존 이력과 원래 검색 예외를 보존합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            insertPresentation(databasePath, "session-a", "older-rule", OLD_TIMESTAMP)
            val before = rows(databasePath)
            val invalidRequest =
                GuidelinePresentationRequest(
                    sessionId = SessionId("session-a"),
                    cwd = project.cwd,
                    filepaths = listOf(Path.of("relative/OrderService.kt")),
                )

            val failure =
                shouldThrow<InvalidSearchRequestException> {
                  presentationService(project.cwd, databasePath).present(invalidRequest)
                }

            failure.message.orEmpty() shouldContain "파일 경로"
            rows(databasePath) shouldBe before
          }
        }

        scenario("검색 중 YAML 오류가 나면 기존 이력은 기록하거나 변경하지 않습니다.") {
          withPresentationProject { project ->
            writeRawGuideline(project.cwd, "rules/guideline.yaml", "id: [\n")
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            insertPresentation(databasePath, "session-a", "older-rule", OLD_TIMESTAMP)
            val before = rows(databasePath)

            shouldThrow<GuidelineLoadException> {
              presentationService(project.cwd, databasePath).present(request(project, "session-a"))
            }

            rows(databasePath) shouldBe before
          }
        }

        scenario("SQLite가 아닌 파일을 열면 원인을 보존한 손상 예외를 전달합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            Files.writeString(databasePath, "SQLite 데이터베이스가 아닙니다.")

            val failure =
                shouldThrow<PresentationDatabaseCorruptException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause shouldNotBe null
          }
        }

        scenario("SQLite schema가 손상되면 원인을 보존한 손상 예외를 전달합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            corruptPresentationSchema(databasePath)

            val failure =
                shouldThrow<PresentationDatabaseCorruptException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause shouldNotBe null
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                CorruptPresentationRowScenario(
                    name = "저장 시각이 올바르지 않으면 손상 예외와 원인을 전달합니다.",
                    guidelineId = "service-rule",
                    presentedAt = "어제 오후",
                    duplicate = false,
                ),
                CorruptPresentationRowScenario(
                    name = "저장 Guideline ID가 올바르지 않으면 손상 예외와 원인을 전달합니다.",
                    guidelineId = "",
                    presentedAt = OLD_TIMESTAMP,
                    duplicate = false,
                ),
                CorruptPresentationRowScenario(
                    name = "같은 이력이 중복 저장되어 있으면 손상 예외와 원인을 전달합니다.",
                    guidelineId = "service-rule",
                    presentedAt = OLD_TIMESTAMP,
                    duplicate = true,
                ),
            ),
        ) { scenario ->
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath, primaryKey = !scenario.duplicate)
            insertPresentation(
                databasePath,
                "session-a",
                scenario.guidelineId,
                scenario.presentedAt,
            )
            if (scenario.duplicate) {
              insertPresentation(
                  databasePath,
                  "session-a",
                  scenario.guidelineId,
                  scenario.presentedAt,
              )
            }

            val failure =
                shouldThrow<PresentationDatabaseCorruptException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause shouldNotBe null
          }
        }

        scenario("DB 상위 경로가 파일이면 환경 오류와 원인을 전달합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val parentFile = project.cwd.resolve("not-a-directory")
            Files.writeString(parentFile, "파일입니다.")
            val databasePath = parentFile.resolve("history.sqlite")

            val failure =
                shouldThrow<PresentationStorageUnavailableException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause shouldNotBe null
          }
        }

        scenario("두 번째 INSERT가 trigger에서 실패하면 앞선 기록까지 모두 rollback합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "alpha/guideline.yaml",
                id = "alpha-rule",
                pattern = "OrderService",
            )
            writeGuideline(
                project.cwd,
                "zeta/guideline.yaml",
                id = "zeta-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            createSecondInsertFailureTrigger(databasePath, "zeta-rule")

            val failure =
                shouldThrow<PresentationStorageException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause shouldNotBe null
            rows(databasePath) shouldBe emptyList()
          }
        }

        scenario("trigger가 자동 rollback한 뒤 명시 rollback도 실패하면 두 원인을 보존합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "alpha/guideline.yaml",
                id = "alpha-rule",
                pattern = "OrderService",
            )
            writeGuideline(
                project.cwd,
                "zeta/guideline.yaml",
                id = "zeta-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            createSecondInsertFailureTrigger(databasePath, "zeta-rule", rollback = true)

            val failure =
                shouldThrow<PresentationTransactionRollbackException> {
                  presentationService(project.cwd, databasePath)
                      .present(request(project, "session-a"))
                }

            failure.cause.shouldBeInstanceOf<SQLException>()
            failure.suppressed shouldHaveSize 1
            failure.suppressed.single().shouldBeInstanceOf<SQLException>()
            rows(databasePath) shouldBe emptyList()
          }
        }

        scenario("SQLite RAISE(ROLLBACK)은 행을 되돌리고 뒤이은 ROLLBACK을 실패시킵니다.") {
          withPresentationProject { project ->
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            createSecondInsertFailureTrigger(databasePath, "zeta-rule", rollback = true)
            val connection = openDatabase(databasePath)
            try {
              connection.createStatement().use { statement ->
                statement.execute("BEGIN IMMEDIATE")
              }
              insertPresentation(
                  connection,
                  "session-a",
                  "alpha-rule",
                  OLD_TIMESTAMP,
              )

              shouldThrow<SQLException> {
                insertPresentation(
                    connection,
                    "session-a",
                    "zeta-rule",
                    FIXED_TIMESTAMP,
                )
              }
              shouldThrow<SQLException> {
                connection.createStatement().use { statement ->
                  statement.execute("ROLLBACK")
                }
              }
            } finally {
              connection.close()
            }

            rows(databasePath) shouldBe emptyList()
          }
        }

        scenario("다른 연결이 write lock을 잡으면 bounded busy 대기 뒤 재시도 예외와 원인을 전달합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            val lockConnection = openDatabase(databasePath)
            lockConnection.createStatement().use { statement ->
              statement.execute("BEGIN IMMEDIATE")
            }

            val failure =
                try {
                  shouldThrow<RetryablePresentationStorageException> {
                    presentationService(project.cwd, databasePath)
                        .present(request(project, "session-a"))
                  }
                } finally {
                  lockConnection.createStatement().use { statement ->
                    statement.execute("ROLLBACK")
                  }
                  lockConnection.close()
                }

            failure.cause shouldNotBe null
            rows(databasePath) shouldBe emptyList()
          }
        }

        scenario("commit 시 다른 연결의 shared read lock이 남아 있으면 기록을 rollback합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            val reader = openDatabase(databasePath)
            reader.createStatement().use { statement ->
              statement.execute("BEGIN")
              statement.executeQuery("SELECT COUNT(*) FROM presentation").use { resultSet ->
                resultSet.next() shouldBe true
                resultSet.getInt(1) shouldBe 0
              }
            }

            val failure =
                try {
                  shouldThrow<RetryablePresentationStorageException> {
                    presentationService(project.cwd, databasePath)
                        .present(request(project, "session-a"))
                  }
                } finally {
                  reader.createStatement().use { statement ->
                    statement.execute("ROLLBACK")
                  }
                  reader.close()
                }

            failure.cause shouldNotBe null
            rows(databasePath) shouldBe emptyList()
          }
        }

        scenario("저장 callback 자체의 예외는 원래 타입을 보존하고 추가 행을 rollback합니다.") {
          withPresentationProject { project ->
            val databasePath = project.cwd.resolve("history.sqlite")
            createPresentationTable(databasePath)
            insertPresentation(databasePath, "session-a", "older-rule", OLD_TIMESTAMP)
            val before = rows(databasePath)
            val failure = IllegalArgumentException("callback 오류")
            val newPresentation =
                Presentation(
                    SessionId("session-a"),
                    GuidelineId("new-rule"),
                    FIXED_INSTANT,
                )
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)

            val thrown =
                shouldThrow<IllegalArgumentException> {
                  unitOfWork.execute {
                    repository.record(listOf(newPresentation))
                    throw failure
                  }
                }

            thrown shouldBe failure
            rows(databasePath) shouldBe before
          }
        }
      }

      feature("독립 JVM의 Presentation 저장소") {
        scenario("임시 user.home의 기본 경로에 기록하고 재시작 뒤 같은 Session을 제외합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "service-rule",
                pattern = "OrderService",
            )
            val temporaryHome = Files.createTempDirectory("guidebook-user-home-")
            try {
              val firstOutput =
                  runProbe(
                      temporaryHome,
                      listOf(
                          "present",
                          project.cwd.toString(),
                          project.sourceFile.toString(),
                          "session-a",
                      ),
                  )
              val secondOutput =
                  runProbe(
                      temporaryHome,
                      listOf(
                          "present",
                          project.cwd.toString(),
                          project.sourceFile.toString(),
                          "session-a",
                      ),
                  )
              val databasePath = temporaryHome.resolve(".adaptive-guidebook/adaptive-guidebook.db")

              firstOutput shouldBe "RESULT|service-rule"
              secondOutput shouldBe "RESULT|"
              rows(databasePath, "session-a") shouldHaveSize 1
            } finally {
              deleteTree(temporaryHome)
            }
          }
        }

        scenario("두 독립 JVM 요청을 READY/GO로 함께 시작하면 Guideline을 최대 한 번 반환합니다.") {
          withPresentationProject { project ->
            writeGuideline(
                project.cwd,
                "rules/guideline.yaml",
                id = "single-rule",
                pattern = "OrderService",
            )
            val temporaryHome = Files.createTempDirectory("guidebook-concurrent-home-")
            val coordinationDirectory = Files.createDirectory(temporaryHome.resolve("coordination"))
            val readyFiles =
                listOf(
                    coordinationDirectory.resolve("one.ready"),
                    coordinationDirectory.resolve("two.ready"),
                )
            val goFile = coordinationDirectory.resolve("GO")
            val outputFiles =
                listOf(
                    coordinationDirectory.resolve("one.out"),
                    coordinationDirectory.resolve("two.out"),
                )
            val processes = ArrayList<Process>()
            try {
              readyFiles.indices.forEach { index ->
                processes.add(
                    startProbe(
                        temporaryHome,
                        listOf(
                            "concurrent",
                            project.cwd.toString(),
                            project.sourceFile.toString(),
                            "session-a",
                            readyFiles[index].toString(),
                            goFile.toString(),
                        ),
                        outputFiles[index],
                    )
                )
              }
              awaitFiles(coordinationDirectory, readyFiles)
              Files.writeString(goFile, "GO", StandardCharsets.UTF_8)
              processes.forEach { process ->
                waitForProbe(process)
                process.exitValue() shouldBe 0
              }
              val outcomes = outputFiles.map(::probeResult)
              val nonEmptyResults = outcomes.filter { it == "RESULT|single-rule" }

              nonEmptyResults shouldHaveSize 1
              outcomes.count { it == "RESULT|" || it == "RETRYABLE" } shouldBe 1
              rows(
                  temporaryHome.resolve(".adaptive-guidebook/adaptive-guidebook.db"),
                  "session-a",
              ) shouldHaveSize 1
            } finally {
              processes.forEach(::stopProbe)
              deleteTree(temporaryHome)
            }
          }
        }
      }
    })

private data class PresentationProject(
    val cwd: Path,
    val sourceFile: Path,
)

private data class StoredPresentation(
    val sessionId: String,
    val guidelineId: String,
    val presentedAt: String,
)

private data class SchemaColumn(
    val name: String,
    val primaryKeyPosition: Int,
)

private data class CorruptPresentationRowScenario(
    val name: String,
    val guidelineId: String,
    val presentedAt: String,
    val duplicate: Boolean,
)

object PresentationIntegrationJvmProbe {
  @JvmStatic
  fun main(arguments: Array<String>) {
    val mode = arguments[0]
    val cwd = Path.of(arguments[1])
    val sourceFile = Path.of(arguments[2])
    val sessionId = arguments[3]

    if (mode == "concurrent") {
      awaitGo(Path.of(arguments[4]), Path.of(arguments[5]))
    }

    val result =
        try {
          GuidelinePresentationFactory.fileSystem()
              .present(
                  GuidelinePresentationRequest(
                      sessionId = SessionId(sessionId),
                      cwd = cwd,
                      filepaths = listOf(sourceFile),
                  )
              )
        } catch (exception: RetryablePresentationStorageException) {
          if (mode == "concurrent") {
            println("RETRYABLE")
            return
          }
          throw exception
        } catch (exception: NotImplementedError) {
          println("NOT_IMPLEMENTED")
          return
        }

    println("RESULT|${result.joinToString(",") { it.id.value }}")
  }

  private fun awaitGo(readyFile: Path, goFile: Path) {
    val directory = goFile.parent
    Files.createDirectories(directory)
    FileSystems.getDefault().newWatchService().use { watcher ->
      directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
      Files.writeString(readyFile, "READY", StandardCharsets.UTF_8)
      while (!Files.exists(goFile)) {
        val key = watcher.poll(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        checkNotNull(key) { "GO 신호를 기다리는 시간이 초과되었습니다." }
        key.pollEvents()
        check(key.reset()) { "GO 신호 감시를 계속할 수 없습니다." }
      }
    }
  }
}

private fun presentationService(
    cwd: Path,
    databasePath: Path,
    clock: Clock = FIXED_CLOCK,
): GuidelinePresentationService {
  val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))

  return GuidelinePresentationService(
      guidelineSearchService = GuidelineSearchFactory.fileSystem(),
      unitOfWork = unitOfWork,
      presentationRepository = SqlitePresentationRepository(unitOfWork),
      clock = clock,
  )
}

private fun request(
    project: PresentationProject,
    sessionId: String,
): GuidelinePresentationRequest =
    GuidelinePresentationRequest(
        sessionId = SessionId(sessionId),
        cwd = project.cwd,
        filepaths = listOf(project.sourceFile),
    )

private fun withPresentationProject(action: (PresentationProject) -> Unit) {
  val cwd = Files.createTempDirectory("guidebook-presentation-")

  try {
    runGit(cwd, "init", "--quiet")
    runGit(cwd, "remote", "add", "origin", "https://host/acme/backend-order.git")
    val sourceFile = writeSource(cwd, "src/OrderService.kt", "class OrderService")
    action(PresentationProject(cwd, sourceFile))
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

private fun writeGuideline(
    cwd: Path,
    relativePath: String,
    id: String,
    pattern: String,
    content: String = "서비스 규칙입니다.",
) {
  writeSource(
      cwd,
      ".agents/guides/$relativePath",
      guidelineYaml(id = id, pattern = pattern, content = content),
  )
}

private fun writeRawGuideline(cwd: Path, relativePath: String, content: String) {
  writeSource(cwd, ".agents/guides/$relativePath", content)
}

private fun guidelineYaml(id: String, pattern: String, content: String): String = buildString {
  append("id: '${yamlSingleQuoted(id)}'\n")
  append("scope:\n")
  append("  repositories:\n")
  append("    - '*'\n")
  append("  languages:\n")
  append("    - 'kotlin'\n")
  append("when:\n")
  append("  - 'file_changed'\n")
  append("match:\n")
  append("  any:\n")
  append("    - regex:\n")
  append("        pattern: '${yamlSingleQuoted(pattern)}'\n")
  append("content: |\n")
  content.lines().forEach { line ->
    append("  ")
    append(line)
    append('\n')
  }
}

private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")

private fun createPresentationTable(databasePath: Path, primaryKey: Boolean = true) {
  Files.createDirectories(databasePath.parent)
  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA journal_mode = DELETE").use { resultSet ->
        resultSet.next() shouldBe true
        resultSet.getString(1) shouldBe "delete"
      }
      val primaryKeyClause = if (primaryKey) ", PRIMARY KEY (session_id, guideline_id)" else ""
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS presentation (
              session_id TEXT NOT NULL,
              guideline_id TEXT NOT NULL,
              presented_at TEXT NOT NULL$primaryKeyClause
          )
          """
              .trimIndent()
      )
    }
  }
}

private fun insertPresentation(
    databasePath: Path,
    sessionId: String,
    guidelineId: String,
    presentedAt: String,
) {
  openDatabase(databasePath).use { connection ->
    insertPresentation(connection, sessionId, guidelineId, presentedAt)
  }
}

private fun insertPresentation(
    connection: Connection,
    sessionId: String,
    guidelineId: String,
    presentedAt: String,
) {
  connection
      .prepareStatement(
          "INSERT INTO presentation(session_id, guideline_id, presented_at) VALUES (?, ?, ?)"
      )
      .use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, guidelineId)
        statement.setString(3, presentedAt)
        statement.executeUpdate() shouldBe 1
      }
}

private fun createSecondInsertFailureTrigger(
    databasePath: Path,
    guidelineId: String,
    rollback: Boolean = false,
) {
  val action = if (rollback) "ROLLBACK" else "ABORT"
  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(
          """
          CREATE TRIGGER fail_second_presentation
          BEFORE INSERT ON presentation
          WHEN NEW.guideline_id = '${yamlSingleQuoted(guidelineId)}'
          BEGIN
              SELECT RAISE($action, 'second presentation insert failed');
          END
          """
              .trimIndent()
      )
    }
  }
}

private fun corruptPresentationSchema(databasePath: Path) {
  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement.execute("PRAGMA writable_schema = ON")
      statement.executeUpdate(
          "UPDATE sqlite_master SET sql = 'CREATE TABLE presentation (malformed' WHERE name = 'presentation'"
      ) shouldBe 1
      statement.execute("PRAGMA writable_schema = OFF")
    }
  }
}

private fun rows(databasePath: Path, sessionId: String? = null): List<StoredPresentation> {
  val query =
      if (sessionId == null) {
        "SELECT session_id, guideline_id, presented_at FROM presentation ORDER BY session_id, guideline_id"
      } else {
        "SELECT session_id, guideline_id, presented_at FROM presentation WHERE session_id = ? ORDER BY guideline_id"
      }
  val storedRows = ArrayList<StoredPresentation>()

  openDatabase(databasePath).use { connection ->
    connection.prepareStatement(query).use { statement ->
      if (sessionId != null) {
        statement.setString(1, sessionId)
      }
      statement.executeQuery().use { resultSet ->
        while (resultSet.next()) {
          storedRows.add(
              StoredPresentation(
                  sessionId = resultSet.getString("session_id"),
                  guidelineId = resultSet.getString("guideline_id"),
                  presentedAt = resultSet.getString("presented_at"),
              )
          )
        }
      }
    }
  }

  return storedRows
}

private fun presentationSchema(databasePath: Path): List<SchemaColumn> {
  val columns = ArrayList<SchemaColumn>()

  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info(presentation)").use { resultSet ->
        while (resultSet.next()) {
          columns.add(
              SchemaColumn(
                  name = resultSet.getString("name"),
                  primaryKeyPosition = resultSet.getInt("pk"),
              )
          )
        }
      }
    }
  }

  return columns
}

private fun openDatabase(databasePath: Path): Connection =
    DriverManager.getConnection("jdbc:sqlite:$databasePath")

private fun startProbe(
    userHome: Path,
    arguments: List<String>,
    outputFile: Path,
): Process {
  val runtimeClasspath =
      checkNotNull(System.getProperty("agentGuidebook.testRuntimeClasspath")) {
        "독립 JVM의 test runtime classpath가 전달되지 않았습니다."
      }
  val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
  val command =
      listOf(
          javaExecutable,
          "-Duser.home=$userHome",
          "-cp",
          runtimeClasspath,
          PresentationIntegrationJvmProbe::class.java.name,
      ) + arguments

  return ProcessBuilder(command)
      .redirectErrorStream(true)
      .redirectOutput(outputFile.toFile())
      .start()
}

private fun runProbe(userHome: Path, arguments: List<String>): String {
  val outputFile = Files.createTempFile("guidebook-jvm-probe-", ".out")
  var process: Process? = null

  try {
    val startedProcess = startProbe(userHome, arguments, outputFile)
    process = startedProcess
    waitForProbe(startedProcess)
    startedProcess.exitValue() shouldBe 0
    return probeResult(outputFile)
  } finally {
    process?.let(::stopProbe)
    Files.deleteIfExists(outputFile)
  }
}

private fun waitForProbe(process: Process) {
  if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    process.waitFor(PROBE_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    error("독립 JVM 테스트 프로세스가 제한 시간 안에 끝나지 않았습니다.")
  }
}

private fun stopProbe(process: Process) {
  if (process.isAlive) {
    process.destroyForcibly()
  }
  process.waitFor(PROBE_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

private fun probeResult(outputFile: Path): String =
    Files.readAllLines(outputFile, StandardCharsets.UTF_8).lastOrNull { line ->
      line.startsWith("RESULT|") || line == "RETRYABLE" || line == "NOT_IMPLEMENTED"
    } ?: error("독립 JVM 테스트 프로세스가 결과를 출력하지 않았습니다: ${Files.readString(outputFile)}")

private fun awaitFiles(directory: Path, expectedFiles: Collection<Path>) {
  FileSystems.getDefault().newWatchService().use { watcher ->
    directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
    while (expectedFiles.any { filepath -> !Files.exists(filepath) }) {
      val key = watcher.poll(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      checkNotNull(key) { "독립 JVM READY 신호를 기다리는 시간이 초과되었습니다." }
      key.pollEvents()
      check(key.reset()) { "독립 JVM READY 신호 감시를 계속할 수 없습니다." }
    }
  }
}

private fun deleteTree(root: Path) {
  if (!Files.exists(root)) {
    return
  }

  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

private val FIXED_INSTANT: Instant = Instant.parse("2026-10-18T12:00:00.123Z")
private val FIXED_TIMESTAMP: String = "2026-10-18T12:00:00.123Z"
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
private val OLD_TIMESTAMP: String = "2026-10-17T08:30:00Z"
private const val PROBE_TIMEOUT_SECONDS = 45L
private const val PROBE_TERMINATION_TIMEOUT_SECONDS = 5L
