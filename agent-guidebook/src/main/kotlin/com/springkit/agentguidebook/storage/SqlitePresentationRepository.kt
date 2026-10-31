package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.errors.PresentationDatabaseCorruptException
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.services.PresentationRepository
import java.sql.SQLException
import java.time.DateTimeException
import java.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed typed DSL로 Presentation 이력을 조회하고 기록합니다.
 *
 * 조회와 기록은 주입받은 UnitOfWork의 활성 transaction에서만 수행합니다.
 */
class SqlitePresentationRepository(
    private val unitOfWork: SqlitePresentationUnitOfWork,
) : PresentationRepository {
  /**
   * 지정 Session의 이력을 조회해 도메인 Presentation으로 복원합니다.
   *
   * 조회와 행 검증 중 발생한 SQL 원인은 활성 UnitOfWork에 기록합니다.
   */
  override fun presentations(sessionId: SessionId): List<Presentation> {
    val context = unitOfWork.currentContext()

    return storageCall(context) {
      PresentationTable.selectAll()
          .where { PresentationTable.sessionId eq sessionId.value }
          .map(::restorePresentation)
          .also(::validateUniqueGuidelines)
    }
  }

  /**
   * 새 Presentation 이력을 typed Table insert로 기록합니다.
   *
   * transaction의 commit과 SQL 오류 분류는 UnitOfWork가 맡습니다.
   */
  override fun record(presentations: Collection<Presentation>) {
    val context = unitOfWork.currentContext()

    storageCall(context) {
      for (presentation in presentations) {
        PresentationTable.insert {
          it[PresentationTable.sessionId] = presentation.sessionId.value
          it[PresentationTable.guidelineId] = presentation.guidelineId.value
          it[PresentationTable.presentedAt] = presentation.presentedAt.toString()
        }
      }
    }
  }

  private fun <T> storageCall(
      context: SqlitePresentationTransactionContext,
      operation: () -> T,
  ): T =
      try {
        operation()
      } catch (failure: SQLException) {
        context.repositorySqlFailure = failure
        throw failure
      }

  private fun validateUniqueGuidelines(presentations: List<Presentation>) {
    val guidelineIds = presentations.map { it.guidelineId }
    val hasDuplicateGuidelineIds = guidelineIds.distinct().size != presentations.size
    if (hasDuplicateGuidelineIds) {
      val cause = IllegalStateException("같은 Session에 Guideline ID가 중복 저장되어 있습니다.")
      throw corruptDatabase("Presentation 이력이 중복되었습니다.", cause)
    }
  }

  private fun restorePresentation(row: ResultRow): Presentation {
    val sessionId =
        try {
          SessionId(requiredValue(row, PresentationTable.sessionId))
        } catch (failure: IllegalArgumentException) {
          throw corruptDatabase("Presentation 저장 행의 Session ID 값이 올바르지 않습니다.", failure)
        }
    val guidelineId =
        try {
          GuidelineId(requiredValue(row, PresentationTable.guidelineId))
        } catch (failure: IllegalArgumentException) {
          throw corruptDatabase("Presentation 저장 행의 Guideline ID 값이 올바르지 않습니다.", failure)
        }
    val presentedAt =
        try {
          Instant.parse(requiredValue(row, PresentationTable.presentedAt))
        } catch (failure: DateTimeException) {
          throw corruptDatabase("Presentation 저장 행의 시각이 올바르지 않습니다.", failure)
        }

    return Presentation(sessionId, guidelineId, presentedAt)
  }

  private fun <T : Any> requiredValue(
      row: ResultRow,
      column: Column<T>,
  ): T =
      row.getOrNull(column)
          ?: throw corruptDatabase(
              "Presentation 저장 행의 ${column.name} 값이 없습니다.",
              IllegalStateException("저장 값은 비어 있을 수 없습니다."),
          )

  private fun corruptDatabase(
      message: String,
      cause: Throwable,
  ): PresentationDatabaseCorruptException = PresentationDatabaseCorruptException(message, cause)
}
