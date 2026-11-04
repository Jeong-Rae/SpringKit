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

class SqliteRepository : Repository {
  override fun provisions(sessionId: SessionId): List<Provision> =
      ProvisionTable.selectAll()
          .where { ProvisionTable.sessionId eq sessionId.value }
          .map(::restoreProvision)

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
