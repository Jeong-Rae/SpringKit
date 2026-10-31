package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.config.SqlitePresentationConfig
import com.springkit.agentguidebook.services.UnitOfWork
import java.sql.SQLException
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed transaction으로 Presentation 작업 범위를 관리합니다.
 *
 * Database와 실패 수집기는 각 실행마다 만들어 해제합니다.
 */
class SqlitePresentationUnitOfWork(
    private val config: SqlitePresentationConfig,
) : UnitOfWork {
  /**
   * callback을 별도 Exposed transaction에서 실행하고 commit 뒤 결과를 반환합니다.
   *
   * 현재 transaction 안에서 다시 호출하면 경로 접근 전에 거부합니다.
   */
  override fun <T> execute(operation: () -> T): T {
    if (TransactionManager.currentOrNull() != null) {
      throw IllegalStateException(
          "활성 Exposed transaction 안에서는 Presentation UnitOfWork를 중첩 실행할 수 없습니다."
      )
    }

    val connectionRegistration = PresentationConnectionRegistration()
    val database =
        try {
          config.openDatabase(connectionRegistration)
        } catch (failure: SQLException) {
          throw PresentationStorageFailures.fromSql(failure)
        }

    return try {
      executeTransaction(database, connectionRegistration, operation)
    } finally {
      TransactionManager.closeAndUnregister(database)
    }
  }

  /**
   * 현재 Exposed transaction의 소유 범위를 조회합니다.
   *
   * 활성 context가 없거나 다른 UnitOfWork 소유이면 DB 작업 전에 거부합니다.
   */
  internal fun currentContext(): SqlitePresentationTransactionContext {
    val transaction =
        TransactionManager.currentOrNull()
            ?: throw IllegalStateException(
                "Presentation Repository는 활성 UnitOfWork 안에서만 사용할 수 있습니다."
            )
    val context =
        transaction.getUserData(TRANSACTION_CONTEXT_KEY)
            ?: throw IllegalStateException(
                "현재 Exposed transaction에는 Presentation UnitOfWork 경계가 없습니다."
            )

    val hasMatchingOwnerAndDatabase = context.owner === this && transaction.db === context.database
    if (!hasMatchingOwnerAndDatabase) {
      throw IllegalStateException("Presentation Repository와 활성 UnitOfWork의 소유 경계가 다릅니다.")
    }

    return context
  }

  private fun <T> executeTransaction(
      database: Database,
      connectionRegistration: PresentationConnectionRegistration,
      operation: () -> T,
  ): T {
    var callbackFailure: Throwable? = null
    var context: SqlitePresentationTransactionContext? = null

    try {
      val result =
          transaction(db = database) {
            config.initializeSchema()
            /**
             * SchemaUtils.create는 현재 Exposed block 안에서 내부 commit을 호출하고 userData를 비웁니다. 스키마 준비 뒤
             * context를 등록해 callback의 이력 조회와 기록을 하나의 원자적 transaction으로 관리합니다.
             */
            val transactionContext =
                SqlitePresentationTransactionContext(this@SqlitePresentationUnitOfWork, database)
            context = transactionContext
            putUserData(TRANSACTION_CONTEXT_KEY, transactionContext)

            try {
              operation()
            } catch (failure: Throwable) {
              callbackFailure = failure
              throw failure
            }
          }

      connectionRegistration.closeFailure?.let { throw PresentationStorageFailures.fromSql(it) }
      return result
    } catch (failure: Throwable) {
      val isRepositorySqlFailure =
          failure is SQLException && context?.repositorySqlFailure === failure
      val isOriginalCallbackFailure = failure === callbackFailure && !isRepositorySqlFailure
      val rollbackFailure = connectionRegistration.rollbackFailure
      val closeFailure = connectionRegistration.closeFailure

      if (rollbackFailure != null) {
        throw PresentationStorageFailures.rollbackFailure(
            originalFailure = originalFailure(failure, isOriginalCallbackFailure),
            rollbackFailure = rollbackFailure,
            closeFailure = closeFailure,
        )
      }

      if (isOriginalCallbackFailure) {
        PresentationStorageFailures.addSuppressed(failure, closeFailure)
        throw failure
      }

      if (failure is SQLException) {
        throw PresentationStorageFailures.fromSql(failure).also {
          PresentationStorageFailures.addSuppressed(it, closeFailure)
        }
      }

      PresentationStorageFailures.addSuppressed(failure, closeFailure)
      throw failure
    }
  }

  private fun originalFailure(failure: Throwable, isOriginalCallbackFailure: Boolean): Throwable =
      when {
        isOriginalCallbackFailure -> failure
        failure is SQLException -> PresentationStorageFailures.sqlCause(failure)
        else -> failure
      }

  private companion object {
    val TRANSACTION_CONTEXT_KEY = Key<SqlitePresentationTransactionContext>()
  }
}
