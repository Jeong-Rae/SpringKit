package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.services.PresentationRepository
import com.springkit.agentguidebook.services.PresentationTransaction
import java.nio.file.Path

/**
 * SQLite 파일에 Session별 Presentation 이력을 저장하는 저장소입니다.
 *
 * 통합 테스트는 생성자에 임시 데이터베이스 경로를 전달합니다.
 */
class SqlitePresentationRepository(private val databasePath: Path) : PresentationRepository {
  /**
   * Presentation 변경을 하나의 즉시 트랜잭션으로 수행합니다.
   *
   * 커밋이 성공한 뒤에만 callback 결과를 반환합니다.
   */
  override fun <T> inImmediateTransaction(operation: (PresentationTransaction) -> T): T =
      throw NotImplementedError("SQLite Presentation 저장 동작은 아직 구현되지 않았습니다.")
}
