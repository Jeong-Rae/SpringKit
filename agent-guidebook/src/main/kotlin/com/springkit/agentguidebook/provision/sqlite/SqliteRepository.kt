package com.springkit.agentguidebook.provision.sqlite

import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.provision.Provision
import com.springkit.agentguidebook.provision.Repository
import com.springkit.agentguidebook.provision.SessionId
import java.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed typed DSL로 Provision 이력을 조회하고 기록합니다.
 *
 * 조회와 기록은 활성 Exposed transaction에서 수행합니다.
 */
class SqliteRepository : Repository {
  /** 지정 Session의 이력을 조회해 도메인 Provision으로 복원합니다. */
  override fun provisions(sessionId: SessionId): List<Provision> =
      ProvisionTable.selectAll()
          .where { ProvisionTable.sessionId eq sessionId.value }
          .map(::restoreProvision)

  /** 새 Provision 이력을 typed Table insert로 기록합니다. */
  override fun record(provisions: Collection<Provision>) {
    for (provision in provisions) {
      ProvisionTable.insert {
        it[ProvisionTable.sessionId] = provision.sessionId.value
        it[ProvisionTable.guidelineId] = provision.guidelineId.value
        it[ProvisionTable.providedAt] = provision.providedAt.toString()
      }
    }
  }

  private fun restoreProvision(row: ResultRow): Provision =
      Provision(
          SessionId(row[ProvisionTable.sessionId]),
          GuidelineId(row[ProvisionTable.guidelineId]),
          Instant.parse(row[ProvisionTable.providedAt]),
      )
}
