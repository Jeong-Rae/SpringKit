package com.springkit.agentguidebook.provision.sqlite

import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.provision.Provision
import com.springkit.agentguidebook.provision.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Comparator

class SqliteUnitOfWorkIntegrationTest :
    FeatureSpec({
      feature("Exposed가 관리하는 SQLite UnitOfWork") {
        scenario("새 스키마 준비 뒤 조회와 기록은 callback에서 원자적으로 동작합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("nested/history.sqlite")
            val unitOfWork = SqliteUnitOfWork(SqliteConfig(databasePath))
            val repository = SqliteRepository()
            val sessionId = SessionId("session-a")
            val written = provision("fresh-schema-rule")

            shouldThrow<IllegalStateException> {
              unitOfWork.execute {
                repository.provisions(sessionId) shouldBe emptyList()
                repository.record(listOf(written))
                repository.provisions(sessionId).map { it.guidelineId.value } shouldBe
                    listOf("fresh-schema-rule")
                error("callback을 실패시켜 기록을 rollback합니다.")
              }
            }

            Files.exists(databasePath) shouldBe true
            provisionCount(databasePath) shouldBe 0
            provisionColumns(databasePath) shouldContainExactly
                listOf("session_id", "guideline_id", "provided_at")
          }
        }

        scenario("callback SQL 실패는 다시 실행하지 않고 앞선 기록을 rollback합니다.") {
          withTemporaryDirectory { directory ->
            val databasePath = directory.resolve("history.sqlite")
            val unitOfWork = SqliteUnitOfWork(SqliteConfig(databasePath))
            val repository = SqliteRepository()
            var callbackAttempts = 0

            shouldThrow<SQLException> {
              unitOfWork.execute {
                callbackAttempts += 1
                repository.record(listOf(provision("before-sql-failure")))
                throw SQLException("callback SQL 오류", "SQLITE_CONSTRAINT", 19)
              }
            }

            callbackAttempts shouldBe 1
            provisionCount(databasePath) shouldBe 0
          }
        }
      }
    })

private fun provision(guidelineId: String): Provision =
    Provision(
        sessionId = SessionId("session-a"),
        guidelineId = GuidelineId(guidelineId),
        providedAt = Instant.parse("2026-10-18T12:00:00Z"),
    )

private fun provisionCount(databasePath: Path): Int =
    DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM provision").use { resultSet ->
          resultSet.next() shouldBe true
          resultSet.getInt(1)
        }
      }
    }

private fun provisionColumns(databasePath: Path): List<String> {
  val columns = ArrayList<String>()
  DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info(provision)").use { resultSet ->
        while (resultSet.next()) {
          columns.add(resultSet.getString("name"))
        }
      }
    }
  }
  return columns
}

private fun withTemporaryDirectory(action: (Path) -> Unit) {
  val directory = Files.createTempDirectory("guidebook-unit-of-work-")
  try {
    action(directory)
  } finally {
    Files.walk(directory).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}
