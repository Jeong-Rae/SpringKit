package com.springkit.agentguidebook.client

import com.springkit.agentguidebook.services.GuidelineSearchService
import com.springkit.agentguidebook.storage.FileSystemGuidelineSearchInputReader

/**
 * 실제 파일 시스템을 사용하는 지침 검색 서비스를 조립합니다.
 *
 * 검색 입력은 서비스 호출 시점에 읽기 경계를 통해 조회합니다.
 */
object GuidelineSearchFactory {
  /**
   * 파일 시스템 읽기 경계를 갖춘 검색 서비스를 생성합니다.
   *
   * @return 실제 프로젝트 파일과 Git 저장소 식별자를 읽는 검색 서비스
   */
  fun fileSystem(): GuidelineSearchService =
      GuidelineSearchService(FileSystemGuidelineSearchInputReader())
}
