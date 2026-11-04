package com.springkit.agentguidebook.provision.sqlite

import com.springkit.agentguidebook.provision.UnitOfWork
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed transaction으로 Provision 작업 범위를 관리합니다.
 *
 * 트랜잭션의 수명과 commit/rollback은 Exposed가 관리합니다.
 */
class SqliteUnitOfWork(
    private val config: SqliteConfig,
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
