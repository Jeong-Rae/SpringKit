package com.springkit.agentguidebook.provision.sqlite

import com.springkit.agentguidebook.provision.UnitOfWork
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SqliteUnitOfWork(
    private val config: SqliteConfig,
) : UnitOfWork {
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
