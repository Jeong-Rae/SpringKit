package com.springkit.agentguidebook.client

import com.springkit.agentguidebook.services.GuidelinePresentationService
import com.springkit.agentguidebook.storage.SqlitePresentationRepository
import java.nio.file.Path
import java.time.Clock

/**
 * 파일 시스템 검색과 기본 SQLite 저장소를 연결하는 서비스 조립 지점입니다.
 *
 * 기본 데이터베이스 경로는 사용자 홈 디렉터리 아래에 생성합니다.
 */
object GuidelinePresentationFactory {
  /**
   * 실제 파일 시스템, SQLite, UTC 시스템 시계를 사용하는 서비스를 반환합니다.
   *
   * 경로와 busy timeout을 변경하는 추가 설정은 제공하지 않습니다.
   */
  fun fileSystem(): GuidelinePresentationService =
      GuidelinePresentationService(
          guidelineSearchService = GuidelineSearchFactory.fileSystem(),
          presentationRepository = SqlitePresentationRepository(defaultDatabasePath()),
          clock = Clock.systemUTC(),
      )

  private fun defaultDatabasePath(): Path =
      Path.of(
          System.getProperty("user.home"),
          ".adaptive-guidebook",
          "adaptive-guidebook.db",
      )
}
