package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.models.SessionId

/**
 * Presentation 이력의 조회와 기록을 맡는 애플리케이션 저장소 포트입니다.
 *
 * 각 메서드는 활성 UnitOfWork 경계 안에서 사용합니다.
 */
interface PresentationRepository {
  /**
   * 지정 Session의 Presentation 이력을 조회합니다.
   *
   * 이력이 없으면 빈 목록을 반환합니다.
   */
  fun presentations(sessionId: SessionId): List<Presentation>

  /**
   * 새 Presentation 이력을 기록합니다.
   *
   * transaction 경계는 UnitOfWork가 소유합니다.
   */
  fun record(presentations: Collection<Presentation>)
}
