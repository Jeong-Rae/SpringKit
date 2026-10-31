package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.config.SqlitePresentationConfig
import com.springkit.agentguidebook.services.UnitOfWork

/**
 * Exposed transaction으로 Presentation 작업 범위를 관리합니다.
 *
 * 실행별 Database와 연결 오류 수집은 구현 단계에서 추가합니다.
 */
class SqlitePresentationUnitOfWork(
    private val config: SqlitePresentationConfig,
) : UnitOfWork {
  /**
   * callback을 SQLite transaction 안에서 실행합니다.
   *
   * 구현 단계 전까지 이 adapter는 호출할 수 없습니다.
   */
  override fun <T> execute(operation: () -> T): T =
      throw NotImplementedError("SQLite UnitOfWork transaction은 구현 단계에서 추가합니다.")

  /**
   * 현재 Exposed transaction의 UnitOfWork 소유 경계를 조회합니다.
   *
   * Repository CRUD 구현 단계에서 활성 scope를 확인합니다.
   */
  internal fun currentContext(): Nothing =
      throw NotImplementedError("SQLite UnitOfWork scope 조회는 구현 단계에서 추가합니다.")
}
