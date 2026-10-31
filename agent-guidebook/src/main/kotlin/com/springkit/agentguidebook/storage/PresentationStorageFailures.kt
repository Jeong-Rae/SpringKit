package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.errors.PresentationDatabaseCorruptException
import com.springkit.agentguidebook.errors.PresentationStorageException
import com.springkit.agentguidebook.errors.PresentationStorageUnavailableException
import com.springkit.agentguidebook.errors.PresentationTransactionRollbackException
import com.springkit.agentguidebook.errors.RetryablePresentationStorageException
import java.sql.SQLException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.sqlite.SQLiteErrorCode

/**
 * Presentation transaction의 SQL 원인과 공개 저장 예외를 변환합니다.
 *
 * callback 예외 자체의 타입과 객체 동일성은 변경하지 않습니다.
 */
internal object PresentationStorageFailures {
  /**
   * SQL 오류를 SQLite 결과 코드에 맞는 저장 예외로 변환합니다.
   *
   * Exposed가 감싼 경우 원본 JDBC 예외를 cause로 보존합니다.
   */
  fun fromSql(failure: SQLException): PresentationStorageException {
    val sqlFailure = sqlCause(failure)
    val primaryResultCode = sqlFailure.errorCode and PRIMARY_RESULT_CODE_MASK

    return when (SQLiteErrorCode.getErrorCode(primaryResultCode)) {
      SQLiteErrorCode.SQLITE_BUSY,
      SQLiteErrorCode.SQLITE_LOCKED -> RetryablePresentationStorageException(cause = sqlFailure)
      SQLiteErrorCode.SQLITE_CORRUPT,
      SQLiteErrorCode.SQLITE_NOTADB ->
          PresentationDatabaseCorruptException("Presentation 데이터베이스를 읽을 수 없습니다.", sqlFailure)
      SQLiteErrorCode.SQLITE_PERM,
      SQLiteErrorCode.SQLITE_READONLY,
      SQLiteErrorCode.SQLITE_IOERR,
      SQLiteErrorCode.SQLITE_FULL,
      SQLiteErrorCode.SQLITE_CANTOPEN -> PresentationStorageUnavailableException(cause = sqlFailure)
      else -> PresentationStorageException(cause = sqlFailure)
    }
  }

  /**
   * Exposed SQL 예외에서 JDBC 원인을 꺼냅니다.
   *
   * 직접 발생한 SQLException은 원래 객체를 반환합니다.
   */
  fun sqlCause(failure: SQLException): SQLException =
      (failure as? ExposedSQLException)?.cause as? SQLException ?: failure

  /**
   * rollback 실패를 저장 예외로 나타내며 두 실패 원인을 보존합니다.
   *
   * rollback과 close 오류는 suppressed 원인으로 연결합니다.
   */
  fun rollbackFailure(
      originalFailure: Throwable,
      rollbackFailure: SQLException,
      closeFailure: SQLException?,
  ): PresentationTransactionRollbackException =
      PresentationTransactionRollbackException(
              message = "Presentation 트랜잭션 실패 후 rollback도 실패했습니다.",
              cause = originalFailure,
          )
          .also { exception ->
            addSuppressed(exception, rollbackFailure)
            addSuppressed(exception, closeFailure)
          }

  /**
   * 예외가 자기 자신이나 cause를 중복 보관하지 않도록 suppressed 원인을 추가합니다.
   *
   * 동일한 suppressed 객체가 이미 연결된 경우 다시 추가하지 않습니다.
   */
  fun addSuppressed(
      failure: Throwable,
      suppressed: Throwable?,
  ) {
    if (suppressed == null) return
    val isSameFailure = failure === suppressed
    val isDirectCause = failure.cause === suppressed
    val isAlreadySuppressed = failure.suppressed.any { it === suppressed }
    val shouldAddSuppressed = !isSameFailure && !isDirectCause && !isAlreadySuppressed
    if (shouldAddSuppressed) {
      failure.addSuppressed(suppressed)
    }
  }

  private const val PRIMARY_RESULT_CODE_MASK = 0xFF
}
