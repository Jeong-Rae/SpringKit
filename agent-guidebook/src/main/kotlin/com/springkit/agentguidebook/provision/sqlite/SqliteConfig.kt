package com.springkit.agentguidebook.provision.sqlite

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.sqlite.SQLiteConfig

/** 생성 시 파일 시스템이나 데이터베이스에 접근하지 않습니다. */
class SqliteConfig(
    val databasePath: Path = defaultDatabasePath(),
) {
  /** JDBC 연결은 반환된 Database의 transaction이 시작될 때 생성합니다. */
  internal fun openDatabase(): Database {
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
            },
    )
  }

  /** UnitOfWork가 transaction을 연 뒤 호출해야 합니다. */
  internal fun initializeSchema() {
    SchemaUtils.create(ProvisionTable)
  }

  private fun prepareDatabasePath(): Path {
    val normalizedPath = databasePath.toAbsolutePath().normalize()
    normalizedPath.parent?.let(Files::createDirectories)
    return normalizedPath
  }

  private companion object {
    const val BUSY_TIMEOUT_MILLISECONDS = 1_000
    const val MAX_ATTEMPTS = 1

    fun defaultDatabasePath(): Path =
        Path.of(
            System.getProperty("user.home"),
            ".adaptive-guidebook",
            "adaptive-guidebook.db",
        )
  }
}
