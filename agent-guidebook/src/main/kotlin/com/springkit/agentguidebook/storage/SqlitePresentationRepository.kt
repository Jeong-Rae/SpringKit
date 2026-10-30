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
import java.sql.SQLException
import java.time.DateTimeException
import java.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.CompositeID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode

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
    val normalizedDatabasePath = prepareDatabasePath()
    val connectionFailures = PresentationConnectionRegistration()
    val database = createDatabase(normalizedDatabasePath, connectionFailures)

    try {
      return executeTransaction(database, connectionFailures, operation)
    } finally {
      TransactionManager.closeAndUnregister(database)
    }
  }

  private fun prepareDatabasePath(): Path {
    try {
      val normalizedPath = databasePath.toAbsolutePath().normalize()
      normalizedPath.parent?.let { Files.createDirectories(it) }
      return normalizedPath
    } catch (failure: IOException) {
      throw PresentationStorageUnavailableException(cause = failure)
    } catch (failure: SecurityException) {
      throw PresentationStorageUnavailableException(cause = failure)
    }
  }

  private fun createDatabase(
      normalizedDatabasePath: Path,
      failures: PresentationConnectionRegistration,
  ): Database {
    val sqliteConfig =
        SQLiteConfig().apply {
          setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS)
          setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }

    return try {
      Database.connect(
          getNewConnection = {
            sqliteConfig.createConnection("jdbc:sqlite:$normalizedDatabasePath")
          },
          databaseConfig =
              DatabaseConfig {
                defaultMaxAttempts = 1
                defaultMinRetryDelay = 0L
                defaultMaxRetryDelay = 0L
              },
          connectionAutoRegistration = failures,
      )
    } catch (failure: SQLException) {
      throw storageException(sqlCause(failure))
    }
  }

  private fun <T> executeTransaction(
      database: Database,
      connectionFailures: PresentationConnectionRegistration,
      operation: (PresentationTransaction) -> T,
  ): T {
    var storageSqlFailure: SQLException? = null
    var callbackSqlFailure: SQLException? = null

    fun <R> storageCall(action: () -> R): R =
        try {
          action()
        } catch (failure: SQLException) {
          storageSqlFailure = failure
          throw failure
        }

    try {
      val result =
          transaction(db = database) {
            maxAttempts = 1
            storageCall { SchemaUtils.create(PresentationTable) }
            val presentationTransaction =
                object : PresentationTransaction {
                  override fun presentations(sessionId: SessionId): List<Presentation> =
                      storageCall {
                        readPresentations(sessionId)
                      }

                  override fun record(presentations: Collection<Presentation>) {
                    storageCall { recordPresentations(presentations) }
                  }
                }
            try {
              operation(presentationTransaction)
            } catch (failure: SQLException) {
              val isStorageSqlFailure = failure === storageSqlFailure
              if (!isStorageSqlFailure) callbackSqlFailure = failure
              throw failure
            }
          }
      connectionFailures.closeFailure?.let { throw storageException(it) }
      return result
    } catch (failure: Throwable) {
      val rollbackFailure = connectionFailures.rollbackFailure
      if (rollbackFailure != null) {
        throw rollbackException(
            originalFailure(failure, callbackSqlFailure),
            rollbackFailure,
            connectionFailures.closeFailure,
        )
      }

      val closeFailure = connectionFailures.closeFailure
      val isStorageSqlFailure = failure is SQLException && failure !== callbackSqlFailure
      if (isStorageSqlFailure) {
        val sqlCause = sqlCause(failure)
        throw storageException(sqlCause).also {
          if (sqlCause !== closeFailure) addSuppressed(it, closeFailure)
        }
      }
      addSuppressed(failure, closeFailure)
      throw failure
    }
  }

  private fun readPresentations(sessionId: SessionId): List<Presentation> {
    val presentations =
        PresentationTable.selectAll()
            .where { PresentationTable.sessionId eq sessionId.value }
            .map(::restorePresentation)

    val guidelineIds = presentations.map { it.guidelineId }
    val hasDuplicateGuidelineIds = guidelineIds.distinct().size != presentations.size
    if (hasDuplicateGuidelineIds) {
      val cause = IllegalStateException("같은 Session에 Guideline ID가 중복 저장되어 있습니다.")
      throw corruptDatabase("Presentation 이력이 중복되었습니다.", cause)
    }

    return presentations
  }

  private fun restorePresentation(row: ResultRow): Presentation {
    val sessionId =
        try {
          SessionId(requiredValue(row, PresentationTable.sessionId).value)
        } catch (failure: IllegalArgumentException) {
          throw corruptDatabase("Presentation 저장 행의 Session ID 값이 올바르지 않습니다.", failure)
        }
    val guidelineId =
        try {
          GuidelineId(requiredValue(row, PresentationTable.guidelineId).value)
        } catch (failure: IllegalArgumentException) {
          throw corruptDatabase("Presentation 저장 행의 Guideline ID 값이 올바르지 않습니다.", failure)
        }
    val presentedAt =
        try {
          Instant.parse(requiredValue(row, PresentationTable.presentedAt))
        } catch (failure: DateTimeException) {
          throw corruptDatabase("Presentation 저장 행의 시각이 올바르지 않습니다.", failure)
        }

    return Presentation(sessionId, guidelineId, presentedAt)
  }

  private fun recordPresentations(presentations: Collection<Presentation>) {
    for (presentation in presentations) {
      PresentationRow.new(compositeId(presentation.sessionId, presentation.guidelineId)) {
        presentedAt = presentation.presentedAt.toString()
      }
    }
  }

  private fun compositeId(sessionId: SessionId, guidelineId: GuidelineId): CompositeID =
      CompositeID {
        it[PresentationTable.sessionId] = sessionId.value
        it[PresentationTable.guidelineId] = guidelineId.value
      }

  private fun <T : Any> requiredValue(row: ResultRow, column: Column<T>): T =
      row.getOrNull(column)
          ?: throw corruptDatabase(
              "Presentation 저장 행의 ${column.name} 값이 없습니다.",
              IllegalStateException("저장 값은 비어 있을 수 없습니다."),
          )

  private fun storageException(failure: SQLException): PresentationStorageException {
    val primaryResultCode =
        SQLiteErrorCode.getErrorCode(failure.errorCode and PRIMARY_RESULT_CODE_MASK)

    return when (primaryResultCode) {
      SQLiteErrorCode.SQLITE_BUSY,
      SQLiteErrorCode.SQLITE_LOCKED -> RetryablePresentationStorageException(cause = failure)
      SQLiteErrorCode.SQLITE_CORRUPT,
      SQLiteErrorCode.SQLITE_NOTADB -> corruptDatabase("Presentation 데이터베이스를 읽을 수 없습니다.", failure)
      SQLiteErrorCode.SQLITE_PERM,
      SQLiteErrorCode.SQLITE_READONLY,
      SQLiteErrorCode.SQLITE_IOERR,
      SQLiteErrorCode.SQLITE_FULL,
      SQLiteErrorCode.SQLITE_CANTOPEN -> PresentationStorageUnavailableException(cause = failure)
      else -> PresentationStorageException(cause = failure)
    }
  }

  private fun sqlCause(failure: SQLException): SQLException =
      (failure as? ExposedSQLException)?.cause as? SQLException ?: failure

  private fun originalFailure(
      failure: Throwable,
      callbackSqlFailure: SQLException?,
  ): Throwable =
      if (failure is SQLException && failure !== callbackSqlFailure) sqlCause(failure) else failure

  private fun rollbackException(
      originalFailure: Throwable,
      rollbackFailure: SQLException,
      closeFailure: SQLException?,
  ): PresentationTransactionRollbackException {
    val rollbackException =
        PresentationTransactionRollbackException(
            message = "Presentation 트랜잭션 실패 후 rollback도 실패했습니다.",
            cause = originalFailure,
        )
    rollbackException.addSuppressed(rollbackFailure)
    closeFailure?.takeIf { it !== rollbackFailure }?.let(rollbackException::addSuppressed)
    return rollbackException
  }

  private fun addSuppressed(failure: Throwable, suppressed: Throwable?) {
    if (suppressed == null) return
    val isSameFailure = failure === suppressed
    val isAlreadyCause = failure.cause === suppressed
    if (!isSameFailure && !isAlreadyCause) failure.addSuppressed(suppressed)
  }

  private fun corruptDatabase(
      message: String,
      cause: Throwable,
  ): PresentationDatabaseCorruptException = PresentationDatabaseCorruptException(message, cause)

  private companion object {
    const val BUSY_TIMEOUT_MILLISECONDS = 1_000
    const val PRIMARY_RESULT_CODE_MASK = 0xFF
  }
}
