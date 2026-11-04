package com.springkit.agentguidebook.provision

/**
 * Provision 이력의 조회와 기록을 맡는 애플리케이션 저장소 포트입니다.
 *
 * 각 메서드는 활성 UnitOfWork 경계 안에서 사용합니다.
 */
interface Repository {
  /**
   * 지정 Session의 Provision 이력을 조회합니다.
   *
   * 이력이 없으면 빈 목록을 반환합니다.
   */
  fun provisions(sessionId: SessionId): List<Provision>

  /**
   * 새 Provision 이력을 기록합니다.
   *
   * transaction 경계는 UnitOfWork가 소유합니다.
   */
  fun record(provisions: Collection<Provision>)
}
