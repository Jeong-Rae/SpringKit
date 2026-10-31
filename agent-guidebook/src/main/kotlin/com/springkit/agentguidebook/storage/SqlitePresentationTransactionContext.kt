package com.springkit.agentguidebook.storage

import java.sql.SQLException
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * 한 Exposed transaction의 저장소 소유권과 실패 원인을 보관합니다.
 *
 * 인스턴스는 transaction의 user data에만 연결됩니다.
 */
internal class SqlitePresentationTransactionContext(
    val owner: SqlitePresentationUnitOfWork,
    val database: Database,
) {
  var repositorySqlFailure: SQLException? = null
}
