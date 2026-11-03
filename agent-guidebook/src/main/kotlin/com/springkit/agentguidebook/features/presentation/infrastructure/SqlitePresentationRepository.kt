package com.springkit.agentguidebook.features.presentation.infrastructure

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.features.presentation.application.PresentationRepository
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import java.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed typed DSL로 Presentation 이력을 조회하고 기록합니다.
 *
 * 조회와 기록은 활성 Exposed transaction에서 수행합니다.
 */
class SqlitePresentationRepository : PresentationRepository {
  /** 지정 Session의 이력을 조회해 도메인 Presentation으로 복원합니다. */
  override fun presentations(sessionId: SessionId): List<Presentation> =
      PresentationTable.selectAll()
          .where { PresentationTable.sessionId eq sessionId.value }
          .map(::restorePresentation)

  /** 새 Presentation 이력을 typed Table insert로 기록합니다. */
  override fun record(presentations: Collection<Presentation>) {
    for (presentation in presentations) {
      PresentationTable.insert {
        it[PresentationTable.sessionId] = presentation.sessionId.value
        it[PresentationTable.guidelineId] = presentation.guidelineId.value
        it[PresentationTable.presentedAt] = presentation.presentedAt.toString()
      }
    }
  }

  private fun restorePresentation(row: ResultRow): Presentation =
      Presentation(
          SessionId(row[PresentationTable.sessionId]),
          GuidelineId(row[PresentationTable.guidelineId]),
          Instant.parse(row[PresentationTable.presentedAt]),
      )
}
