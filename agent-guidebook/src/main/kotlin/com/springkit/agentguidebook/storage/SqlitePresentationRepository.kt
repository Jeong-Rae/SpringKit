package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.errors.PresentationDatabaseCorruptException
import com.springkit.agentguidebook.errors.PresentationStorageException
import com.springkit.agentguidebook.errors.PresentationStorageUnavailableException
import com.springkit.agentguidebook.errors.PresentationTransactionRollbackException
import com.springkit.agentguidebook.errors.RetryablePresentationStorageException
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.services.PresentationRepository
import com.springkit.agentguidebook.services.PresentationTransaction
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.DateTimeException
import java.time.Instant

/**
 * SQLite 파일에 Session별 Presentation 이력을 저장하는 저장소입니다.
 *
 * 통합 테스트는 생성자에 임시 데이터베이스 경로를 전달합니다.
 */
class SqlitePresentationRepository(private val databasePath: Path) : PresentationRepository {
  /**
   * Presentation 변경을 하나의 즉시 트랜잭션으로 수행합니다.
   *
   * 커밋이 성공한 뒤에만 callback 결과를 반환합니다.
   */
  override fun <T> inImmediateTransaction(operation: (PresentationTransaction) -> T): T {
    val normalizedDatabasePath = normalizeDatabasePath()
    createDatabaseDirectory(normalizedDatabasePath)
    val connection = openConnection(normalizedDatabasePath)

    return try {
      connection.use { openedConnection ->
        executeImmediateTransaction(openedConnection, operation)
      }
    } catch (failure: SqliteOperationFailure) {
      val storageFailure = storageException(failure.sqlException)
      failure.suppressed.forEach(storageFailure::addSuppressed)
      throw storageFailure
    } catch (failure: CallbackFailure) {
      failure.suppressed.forEach(failure.callbackFailure::addSuppressed)
      throw failure.callbackFailure
    } catch (failure: SQLException) {
      throw storageException(failure)
    }
  }

  private fun normalizeDatabasePath(): Path =
      try {
        databasePath.toAbsolutePath().normalize()
      } catch (failure: SecurityException) {
        throw PresentationStorageUnavailableException(cause = failure)
      }

  private fun createDatabaseDirectory(normalizedDatabasePath: Path) {
    val parent = normalizedDatabasePath.parent ?: return

    try {
      Files.createDirectories(parent)
    } catch (failure: IOException) {
      throw PresentationStorageUnavailableException(cause = failure)
    } catch (failure: SecurityException) {
      throw PresentationStorageUnavailableException(cause = failure)
    }
  }

  private fun openConnection(normalizedDatabasePath: Path): Connection =
      try {
        DriverManager.getConnection("jdbc:sqlite:$normalizedDatabasePath")
      } catch (failure: SQLException) {
        throw storageException(failure)
      }

  private fun <T> executeImmediateTransaction(
      connection: Connection,
      operation: (PresentationTransaction) -> T,
  ): T {
    var isTransactionStarted = false

    try {
      executeStatement(connection, "PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLISECONDS")
      executeStatement(connection, "BEGIN IMMEDIATE")
      isTransactionStarted = true
      initializeSchema(connection)

      val result = executeCallback(connection, operation)

      executeStatement(connection, "COMMIT")
      isTransactionStarted = false
      return result
    } catch (failure: Throwable) {
      if (isTransactionStarted) {
        rollback(connection, failure)
      }

      throw failure
    }
  }

  private fun initializeSchema(connection: Connection) {
    executeStatement(
        connection,
        """
        CREATE TABLE IF NOT EXISTS presentation (
            session_id TEXT NOT NULL,
            guideline_id TEXT NOT NULL,
            presented_at TEXT NOT NULL,
            PRIMARY KEY (session_id, guideline_id)
        )
        """
            .trimIndent(),
    )
  }

  private fun <T> executeCallback(
      connection: Connection,
      operation: (PresentationTransaction) -> T,
  ): T {
    val transaction =
        object : PresentationTransaction {
          override fun presentations(sessionId: SessionId): List<Presentation> =
              readPresentations(connection, sessionId)

          override fun record(presentations: Collection<Presentation>) {
            recordPresentations(connection, presentations)
          }
        }

    return try {
      operation(transaction)
    } catch (failure: SqliteOperationFailure) {
      throw failure
    } catch (failure: Throwable) {
      throw CallbackFailure(failure)
    }
  }

  private fun readPresentations(connection: Connection, sessionId: SessionId): List<Presentation> {
    val presentations = mutableListOf<Presentation>()
    val guidelineIds = mutableSetOf<GuidelineId>()

    try {
      connection.prepareStatement(SELECT_PRESENTATIONS_FOR_SESSION).use { statement ->
        statement.setString(1, sessionId.value)
        statement.executeQuery().use { resultSet ->
          while (resultSet.next()) {
            val storedSessionId =
                parseStoredValue(
                    "Session ID",
                    readRequiredText(resultSet, "session_id"),
                ) { value ->
                  SessionId(value)
                }
            val storedGuidelineId =
                parseStoredValue(
                    "Guideline ID",
                    readRequiredText(resultSet, "guideline_id"),
                ) { value ->
                  GuidelineId(value)
                }
            val presentedAt = parseStoredTimestamp(readRequiredText(resultSet, "presented_at"))

            val isDuplicateGuideline = !guidelineIds.add(storedGuidelineId)

            if (isDuplicateGuideline) {
              val cause = IllegalStateException("같은 Session에 Guideline ID가 중복 저장되어 있습니다.")
              throw corruptDatabase("Presentation 이력이 중복되었습니다.", cause)
            }

            presentations.add(Presentation(storedSessionId, storedGuidelineId, presentedAt))
          }
        }
      }
    } catch (failure: PresentationDatabaseCorruptException) {
      throw failure
    } catch (failure: SQLException) {
      throw SqliteOperationFailure(failure)
    }

    return presentations
  }

  private fun readRequiredText(resultSet: java.sql.ResultSet, columnName: String): String =
      resultSet.getString(columnName)
          ?: throw corruptDatabase(
              "Presentation 저장 행의 $columnName 값이 없습니다.",
              IllegalStateException("저장 값은 비어 있을 수 없습니다."),
          )

  private fun <T> parseStoredValue(
      label: String,
      value: String,
      parse: (String) -> T,
  ): T =
      try {
        parse(value)
      } catch (failure: IllegalArgumentException) {
        throw corruptDatabase("Presentation 저장 행의 $label 값이 올바르지 않습니다.", failure)
      }

  private fun parseStoredTimestamp(value: String): Instant =
      try {
        Instant.parse(value)
      } catch (failure: DateTimeException) {
        throw corruptDatabase("Presentation 저장 행의 시각이 올바르지 않습니다.", failure)
      }

  private fun recordPresentations(connection: Connection, presentations: Collection<Presentation>) {
    try {
      connection.prepareStatement(INSERT_PRESENTATION).use { statement ->
        for (presentation in presentations) {
          statement.setString(1, presentation.sessionId.value)
          statement.setString(2, presentation.guidelineId.value)
          statement.setString(3, presentation.presentedAt.toString())
          statement.executeUpdate()
        }
      }
    } catch (failure: SQLException) {
      throw SqliteOperationFailure(failure)
    }
  }

  private fun executeStatement(connection: Connection, sql: String) {
    try {
      connection.createStatement().use { statement -> statement.execute(sql) }
    } catch (failure: SQLException) {
      throw SqliteOperationFailure(failure)
    }
  }

  private fun rollback(connection: Connection, failure: Throwable) {
    try {
      executeStatement(connection, "ROLLBACK")
    } catch (rollbackFailure: SqliteOperationFailure) {
      val originalFailure =
          when (failure) {
            is SqliteOperationFailure -> failure.sqlException
            is CallbackFailure -> failure.callbackFailure
            else -> failure
          }
      val rollbackException =
          PresentationTransactionRollbackException(
              message = "Presentation 트랜잭션 실패 후 rollback도 실패했습니다.",
              cause = originalFailure,
          )
      rollbackException.addSuppressed(rollbackFailure.sqlException)
      throw rollbackException
    }
  }

  private fun storageException(failure: SQLException): PresentationStorageException {
    val primaryResultCode = failure.errorCode and PRIMARY_RESULT_CODE_MASK

    return when (primaryResultCode) {
      SQLITE_BUSY,
      SQLITE_LOCKED -> RetryablePresentationStorageException(cause = failure)
      SQLITE_CORRUPT,
      SQLITE_NOTADB -> corruptDatabase("Presentation 데이터베이스를 읽을 수 없습니다.", failure)
      SQLITE_PERM,
      SQLITE_READONLY,
      SQLITE_IOERR,
      SQLITE_FULL,
      SQLITE_CANTOPEN -> PresentationStorageUnavailableException(cause = failure)
      else -> PresentationStorageException(cause = failure)
    }
  }

  private fun corruptDatabase(
      message: String,
      cause: Throwable,
  ): PresentationDatabaseCorruptException = PresentationDatabaseCorruptException(message, cause)

  private class SqliteOperationFailure(val sqlException: SQLException) :
      RuntimeException(sqlException)

  private class CallbackFailure(val callbackFailure: Throwable) : RuntimeException(callbackFailure)

  private companion object {
    const val BUSY_TIMEOUT_MILLISECONDS = 1_000
    const val PRIMARY_RESULT_CODE_MASK = 0xFF
    const val SQLITE_BUSY = 5
    const val SQLITE_LOCKED = 6
    const val SQLITE_PERM = 3
    const val SQLITE_READONLY = 8
    const val SQLITE_IOERR = 10
    const val SQLITE_CORRUPT = 11
    const val SQLITE_FULL = 13
    const val SQLITE_CANTOPEN = 14
    const val SQLITE_NOTADB = 26
    const val SELECT_PRESENTATIONS_FOR_SESSION =
        "SELECT session_id, guideline_id, presented_at FROM presentation WHERE session_id = ? ORDER BY rowid"
    const val INSERT_PRESENTATION =
        "INSERT INTO presentation(session_id, guideline_id, presented_at) VALUES (?, ?, ?)"
  }
}
