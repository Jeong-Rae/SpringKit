package com.springkit.agentguidebook.client

import com.springkit.agentguidebook.config.SqlitePresentationConfig
import com.springkit.agentguidebook.services.GuidelinePresentationService
import com.springkit.agentguidebook.storage.SqlitePresentationRepository
import com.springkit.agentguidebook.storage.SqlitePresentationUnitOfWork
import java.time.Clock

/**
 * 파일 시스템 검색과 SQLite Presentation 어댑터를 연결하는 서비스 조립 지점입니다.
 *
 * 기본 데이터베이스 경로는 사용자 홈 디렉터리 아래에 둡니다.
 */
object GuidelinePresentationFactory {
  /**
   * 실제 파일 시스템, SQLite 설정과 UTC 시스템 시계를 사용하는 서비스를 반환합니다.
   *
   * 조립할 때 디렉터리나 데이터베이스를 만들지 않습니다.
   */
  fun fileSystem(): GuidelinePresentationService {
    val unitOfWork = SqlitePresentationUnitOfWork(SqlitePresentationConfig())

    return GuidelinePresentationService(
        guidelineSearchService = GuidelineSearchFactory.fileSystem(),
        unitOfWork = unitOfWork,
        presentationRepository = SqlitePresentationRepository(unitOfWork),
        clock = Clock.systemUTC(),
    )
  }
}
