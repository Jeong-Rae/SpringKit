package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.services.PresentationRepository

/**
 * Exposed typed query와 DAO로 Presentation 이력을 저장합니다.
 *
 * 조회와 기록은 주입받은 UnitOfWork의 활성 범위 안에서만 수행합니다.
 */
class SqlitePresentationRepository(
    private val unitOfWork: SqlitePresentationUnitOfWork,
) : PresentationRepository {
  /**
   * 지정 Session의 이력을 조회합니다.
   *
   * 실제 조회는 구현 단계에서 추가합니다.
   */
  override fun presentations(sessionId: SessionId): List<Presentation> = unitOfWork.currentContext()

  /**
   * 새 Presentation 이력을 기록합니다.
   *
   * 실제 기록은 구현 단계에서 추가합니다.
   */
  override fun record(presentations: Collection<Presentation>) {
    unitOfWork.currentContext()
  }
}
