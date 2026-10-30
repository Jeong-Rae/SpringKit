package com.springkit.agentguidebook.storage

import java.sql.Connection
import java.sql.SQLException
import org.jetbrains.exposed.v1.jdbc.DatabaseConnectionAutoRegistration
import org.jetbrains.exposed.v1.jdbc.statements.api.ExposedConnection
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcConnectionImpl

/**
 * Exposed JDBC 연결의 rollback과 close 실패를 현재 저장 호출에 보관합니다.
 *
 * 연결당 한 transaction을 사용하고 나머지 동작은 Exposed 기본 구현에 위임합니다.
 */
internal class PresentationConnectionRegistration : DatabaseConnectionAutoRegistration {
  var rollbackFailure: SQLException? = null
  var closeFailure: SQLException? = null

  override fun invoke(connection: Connection): ExposedConnection<*> {
    val jdbcConnection = JdbcConnectionImpl(connection)

    return object : ExposedConnection<Connection> by jdbcConnection {
      override fun rollback() {
        try {
          jdbcConnection.rollback()
        } catch (failure: SQLException) {
          if (rollbackFailure == null) rollbackFailure = failure
          throw failure
        }
      }

      override fun close() {
        try {
          jdbcConnection.close()
        } catch (failure: SQLException) {
          if (closeFailure == null) closeFailure = failure
          throw failure
        }
      }
    }
  }
}
