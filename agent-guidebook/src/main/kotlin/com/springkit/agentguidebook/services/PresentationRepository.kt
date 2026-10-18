package com.springkit.agentguidebook.services

/**
 * Presentation 이력을 하나의 즉시 트랜잭션에서 다루는 저장소 경계입니다.
 *
 * 콜백과 저장 변경은 하나의 commit 단위입니다.
 */
interface PresentationRepository {
  /**
   * 콜백을 커밋하고 커밋이 성공한 뒤에만 결과를 반환합니다.
   *
   * 콜백 오류는 저장소 오류 타입으로 바꾸지 않고 전달합니다.
   */
  fun <T> inImmediateTransaction(operation: (PresentationTransaction) -> T): T
}
