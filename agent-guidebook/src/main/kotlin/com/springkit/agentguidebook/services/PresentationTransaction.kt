package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.models.SessionId

/**
 * 같은 트랜잭션 안에서 Presentation 이력을 조회하고 기록하는 경계입니다.
 *
 * 조회와 명령은 저장소가 연 동일 연결을 사용합니다.
 */
interface PresentationTransaction {
  /**
   * 지정 Session의 Presentation 이력을 읽습니다.
   *
   * 반환 이력은 지정 Session에 한정됩니다.
   */
  fun presentations(sessionId: SessionId): List<Presentation>

  /**
   * 새 Presentation 이력을 기록합니다.
   *
   * 중복 여부와 트랜잭션 원자성은 저장 경계에서 처리합니다.
   */
  fun record(presentations: Collection<Presentation>)
}
