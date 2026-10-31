package com.springkit.agentguidebook.config

import com.springkit.agentguidebook.errors.PresentationStorageUnavailableException
import com.springkit.agentguidebook.storage.PresentationTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.DatabaseConnectionAutoRegistration
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.sqlite.SQLiteConfig

/**
 * SQLite Presentation 저장소의 경로와 연결 설정을 보유합니다.
 *
 * 생성할 때 파일 시스템이나 데이터베이스에 접근하지 않습니다.
 */
class SqlitePresentationConfig(
    val databasePath: Path = defaultDatabasePath(),
) {
  /**
   * 실행별 Exposed Database를 연결 설정과 함께 구성합니다.
   *
   * JDBC 연결은 반환된 Database의 transaction이 시작될 때 생성합니다.
   */
  internal fun openDatabase(
      connectionRegistration: DatabaseConnectionAutoRegistration,
  ): Database {
    val normalizedDatabasePath = prepareDatabasePath()
    val sqliteConfig =
        SQLiteConfig().apply {
          setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS)
          setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }

    return Database.connect(
        getNewConnection = {
          sqliteConfig.createConnection("jdbc:sqlite:$normalizedDatabasePath")
        },
        databaseConfig =
            DatabaseConfig {
              defaultMaxAttempts = MAX_ATTEMPTS
              defaultMinRetryDelay = NO_RETRY_DELAY_MILLISECONDS
              defaultMaxRetryDelay = NO_RETRY_DELAY_MILLISECONDS
            },
        connectionAutoRegistration = connectionRegistration,
    )
  }

  /**
   * 현재 Exposed transaction 안에서 Presentation 테이블을 준비합니다.
   *
   * 이 메서드는 UnitOfWork가 transaction을 연 뒤 호출해야 합니다.
   */
  internal fun initializeSchema() {
    SchemaUtils.create(PresentationTable)
  }

  private fun prepareDatabasePath(): Path {
    try {
      val normalizedPath = databasePath.toAbsolutePath().normalize()
      normalizedPath.parent?.let(Files::createDirectories)
      return normalizedPath
    } catch (failure: IOException) {
      throw PresentationStorageUnavailableException(cause = failure)
    } catch (failure: SecurityException) {
      throw PresentationStorageUnavailableException(cause = failure)
    }
  }

  private companion object {
    const val BUSY_TIMEOUT_MILLISECONDS = 1_000
    const val MAX_ATTEMPTS = 1
    const val NO_RETRY_DELAY_MILLISECONDS = 0L

    fun defaultDatabasePath(): Path =
        Path.of(
            System.getProperty("user.home"),
            ".adaptive-guidebook",
            "adaptive-guidebook.db",
        )
  }
}
