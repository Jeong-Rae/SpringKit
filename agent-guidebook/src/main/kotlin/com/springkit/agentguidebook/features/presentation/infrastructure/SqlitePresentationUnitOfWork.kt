package com.springkit.agentguidebook.features.presentation.infrastructure

import com.springkit.agentguidebook.features.presentation.application.UnitOfWork
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed transaction으로 Presentation 작업 범위를 관리합니다.
 *
 * 트랜잭션의 수명과 commit/rollback은 Exposed가 관리합니다.
 */
class SqlitePresentationUnitOfWork(
    private val config: SqlitePresentationConfig,
) : UnitOfWork {
  /** callback을 Exposed transaction에서 실행하고 commit 뒤 결과를 반환합니다. */
  override fun <T> execute(operation: () -> T): T {
    val database = config.openDatabase()

    return try {
      transaction(db = database) {
        config.initializeSchema()
        operation()
      }
    } finally {
      TransactionManager.closeAndUnregister(database)
    }
  }
}
