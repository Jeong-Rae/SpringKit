package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.config.SqlitePresentationConfig
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.storage.SqlitePresentationRepository
import com.springkit.agentguidebook.storage.SqlitePresentationUnitOfWork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SqlitePresentationUnitOfWorkIntegrationTest :
    FeatureSpec({
      feature("SQLite 저장소의 활성 UnitOfWork 경계") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                RepositoryOperation(
                    name = "활성 UnitOfWork 밖에서 이력을 조회하면, 호출이 거부되고 DB와 상위 경로가 생성되지 않습니다.",
                    operation = { repository -> repository.presentations(SessionId("session-a")) },
                ),
                RepositoryOperation(
                    name = "활성 UnitOfWork 밖에서 빈 이력을 기록하면, 호출이 거부되고 DB와 상위 경로가 생성되지 않습니다.",
                    operation = { repository -> repository.record(emptyList()) },
                ),
                RepositoryOperation(
                    name = "활성 UnitOfWork 밖에서 Presentation을 기록하면, 호출이 거부되고 DB와 상위 경로가 생성되지 않습니다.",
                    operation = { repository ->
                      repository.record(listOf(presentation("outside-rule")))
                    },
                ),
            ),
        ) { scenario ->
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("unused/history.sqlite")
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)

            shouldThrow<IllegalStateException> {
              scenario.operation(repository)
            }

            Files.exists(databasePath) shouldBe false
            Files.exists(databasePath.parent) shouldBe false
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                RepositoryOwnershipOperation(
                    name = "Repository A를 UnitOfWork B 안에서 조회하면, A 경계를 거부하고 B 이력과 A 경로를 보존합니다.",
                    operation = { repository -> repository.presentations(SessionId("session-a")) },
                ),
                RepositoryOwnershipOperation(
                    name = "Repository A를 UnitOfWork B 안에서 기록하면, A 경계를 거부하고 B 이력과 A 경로를 보존합니다.",
                    operation = { repository ->
                      repository.record(listOf(presentation("foreign-rule")))
                    },
                ),
            ),
        ) { scenario ->
          withTemporaryDirectory { directory ->
            val databaseA = directory.resolve("a/history.sqlite")
            val databaseB = directory.resolve("b/history.sqlite")
            val existing = presentation("existing-rule")
            createPresentationTable(databaseB, listOf(existing))
            val unitOfWorkA = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databaseA))
            val unitOfWorkB = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databaseB))
            val repositoryA = SqlitePresentationRepository(unitOfWorkA)

            shouldThrow<IllegalStateException> {
              unitOfWorkB.execute {
                scenario.operation(repositoryA)
              }
            }

            presentationRows(databaseB) shouldContainExactly listOf(storedPresentation(existing))
            Files.exists(databaseA) shouldBe false
            Files.exists(databaseA.parent) shouldBe false
          }
        }

        scenario("같은 UnitOfWork를 중첩하면, 부모 변경을 rollback하고 기존 이력을 유지합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            val existing = presentation("existing-rule")
            createPresentationTable(databasePath, listOf(existing))
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)

            shouldThrow<IllegalStateException> {
              unitOfWork.execute {
                repository.record(listOf(presentation("uncommitted-rule")))
                unitOfWork.execute { "중첩 결과" }
              }
            }

            presentationRows(databasePath) shouldContainExactly listOf(storedPresentation(existing))
          }
        }

        scenario("다른 UnitOfWork를 중첩하면, 부모 변경을 rollback하고 자식 경로를 만들지 않습니다.") {
          withTemporaryDirectory { directory ->
            val databaseParent = directory.resolve("parent/history.sqlite")
            val databaseChild = directory.resolve("child/history.sqlite")
            val existing = presentation("existing-rule")
            createPresentationTable(databaseParent, listOf(existing))
            val parentUnitOfWork =
                SqlitePresentationUnitOfWork(SqlitePresentationConfig(databaseParent))
            val childUnitOfWork =
                SqlitePresentationUnitOfWork(SqlitePresentationConfig(databaseChild))
            val parentRepository = SqlitePresentationRepository(parentUnitOfWork)

            shouldThrow<IllegalStateException> {
              parentUnitOfWork.execute {
                parentRepository.record(listOf(presentation("uncommitted-rule")))
                childUnitOfWork.execute { "자식 결과" }
              }
            }

            presentationRows(databaseParent) shouldContainExactly
                listOf(storedPresentation(existing))
            Files.exists(databaseChild) shouldBe false
            Files.exists(databaseChild.parent) shouldBe false
          }
        }
      }

      feature("SQLite UnitOfWork scope 수명") {
        scenario("정상 scope가 끝나면 Repository 직접 조회를 거부하고 commit된 기록을 유지합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            createPresentationTable(databasePath)
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)
            val committed = presentation("committed-rule")

            val result = unitOfWork.execute {
              repository.record(listOf(committed))
              "commit 완료"
            }

            result shouldBe "commit 완료"
            shouldThrow<IllegalStateException> {
              repository.presentations(SessionId("session-a"))
            }
            presentationRows(databasePath) shouldContainExactly
                listOf(storedPresentation(committed))
          }
        }

        scenario("예외 scope 뒤 직접 기록을 거부하고 같은 어댑터의 다음 요청을 정상 commit합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            createPresentationTable(databasePath)
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)
            val rolledBack = presentation("rolled-back-rule")
            val failure = IllegalArgumentException("Application callback 오류")

            val thrown =
                shouldThrow<IllegalArgumentException> {
                  unitOfWork.execute {
                    repository.record(listOf(rolledBack))
                    throw failure
                  }
                }

            (thrown === failure) shouldBe true
            shouldThrow<IllegalStateException> {
              repository.record(emptyList())
            }
            presentationRows(databasePath) shouldBe emptyList()

            val committed = presentation("committed-after-failure")
            val result = unitOfWork.execute {
              repository.record(listOf(committed))
              listOf(committed.guidelineId.value)
            }

            result shouldContainExactly listOf("committed-after-failure")
            presentationRows(databasePath) shouldContainExactly
                listOf(storedPresentation(committed))
          }
        }

        scenario("callback이 직접 던진 SQLException은 같은 객체로 전달하고 새 기록을 rollback합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            val existing = presentation("existing-rule")
            createPresentationTable(databasePath, listOf(existing))
            val before = presentationRows(databasePath)
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)
            val failure = SQLException("callback SQL 오류", "SQLITE_CONSTRAINT", 19)

            val thrown =
                shouldThrow<SQLException> {
                  unitOfWork.execute {
                    repository.record(listOf(presentation("uncommitted-rule")))
                    throw failure
                  }
                }

            (thrown === failure) shouldBe true
            presentationRows(databasePath) shouldBe before
          }
        }
      }

      feature("SQLite UnitOfWork 동시 사용") {
        scenario("같은 UnitOfWork와 Repository를 두 스레드에서 사용하면, 각 결과와 기록을 보존합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig(databasePath))
            val repository = SqlitePresentationRepository(unitOfWork)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val finished = CountDownLatch(2)
            val results = arrayOfNulls<String>(2)
            val failures = arrayOfNulls<Throwable>(2)
            val presentations = listOf(presentation("thread-rule-a"), presentation("thread-rule-b"))
            val workers = presentations.mapIndexed { index, value ->
              Thread {
                ready.countDown()
                try {
                  check(start.await(30, TimeUnit.SECONDS)) { "동시 요청 시작 신호를 기다리지 못했습니다." }
                  results[index] = unitOfWork.execute {
                    repository.record(listOf(value))
                    value.guidelineId.value
                  }
                } catch (failure: Throwable) {
                  failures[index] = failure
                } finally {
                  finished.countDown()
                }
              }
            }

            try {
              workers.forEach(Thread::start)
              ready.await(30, TimeUnit.SECONDS) shouldBe true
              start.countDown()
              finished.await(45, TimeUnit.SECONDS) shouldBe true
            } finally {
              start.countDown()
              workers.filter { it.isAlive }.forEach(Thread::interrupt)
              workers.forEach { it.join(5_000) }
            }

            failures.toList() shouldBe listOf(null, null)
            results.toList() shouldBe listOf("thread-rule-a", "thread-rule-b")
            presentationRows(databasePath) shouldContainExactly
                presentations.map(::storedPresentation).sortedBy { it.guidelineId }
          }
        }
      }
    })

private data class RepositoryOperation(
    val name: String,
    val operation: (SqlitePresentationRepository) -> Unit,
)

private data class RepositoryOwnershipOperation(
    val name: String,
    val operation: (SqlitePresentationRepository) -> Unit,
)

private data class ObservedPresentationRow(
    val sessionId: String,
    val guidelineId: String,
    val presentedAt: String,
)

private fun presentation(guidelineId: String): Presentation =
    Presentation(
        sessionId = SessionId("session-a"),
        guidelineId = GuidelineId(guidelineId),
        presentedAt = PRESENTED_AT,
    )

private fun storedPresentation(presentation: Presentation): ObservedPresentationRow =
    ObservedPresentationRow(
        sessionId = presentation.sessionId.value,
        guidelineId = presentation.guidelineId.value,
        presentedAt = presentation.presentedAt.toString(),
    )

private fun createPresentationTable(
    databasePath: Path,
    presentations: Collection<Presentation> = emptyList(),
) {
  Files.createDirectories(databasePath.parent)
  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement.execute(
          """
          CREATE TABLE presentation (
              session_id TEXT NOT NULL,
              guideline_id TEXT NOT NULL,
              presented_at TEXT NOT NULL,
              PRIMARY KEY (session_id, guideline_id)
          )
          """
              .trimIndent()
      )
    }
    presentations.forEach { presentation ->
      insertPresentation(connection, presentation)
    }
  }
}

private fun insertPresentation(
    connection: Connection,
    presentation: Presentation,
) {
  connection
      .prepareStatement(
          "INSERT INTO presentation(session_id, guideline_id, presented_at) VALUES (?, ?, ?)"
      )
      .use { statement ->
        statement.setString(1, presentation.sessionId.value)
        statement.setString(2, presentation.guidelineId.value)
        statement.setString(3, presentation.presentedAt.toString())
        statement.executeUpdate() shouldBe 1
      }
}

private fun presentationRows(databasePath: Path): List<ObservedPresentationRow> {
  if (!Files.exists(databasePath)) {
    return emptyList()
  }

  val presentations = ArrayList<ObservedPresentationRow>()
  openDatabase(databasePath).use { connection ->
    connection.createStatement().use { statement ->
      statement
          .executeQuery(
              "SELECT session_id, guideline_id, presented_at FROM presentation ORDER BY session_id, guideline_id"
          )
          .use { resultSet ->
            while (resultSet.next()) {
              presentations.add(
                  ObservedPresentationRow(
                      sessionId = resultSet.getString("session_id"),
                      guidelineId = resultSet.getString("guideline_id"),
                      presentedAt = resultSet.getString("presented_at"),
                  )
              )
            }
          }
    }
  }

  return presentations
}

private fun openDatabase(databasePath: Path): Connection =
    DriverManager.getConnection("jdbc:sqlite:$databasePath")

private fun withTemporaryDirectory(action: (Path) -> Unit) {
  val directory = Files.createTempDirectory("presentation-unit-of-work-")

  try {
    action(directory)
  } finally {
    Files.walk(directory).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}

private val PRESENTED_AT: Instant = Instant.parse("2026-10-18T12:00:00Z")
