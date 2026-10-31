package com.springkit.agentguidebook.services

/**
 * Application 작업의 transaction 경계를 관리하는 포트입니다.
 *
 * callback이 성공하고 commit된 뒤에만 결과를 반환합니다.
 */
interface UnitOfWork {
  /**
   * callback을 한 transaction에서 실행합니다.
   *
   * 실패한 callback은 rollback 대상입니다.
   */
  fun <T> execute(operation: () -> T): T
}
