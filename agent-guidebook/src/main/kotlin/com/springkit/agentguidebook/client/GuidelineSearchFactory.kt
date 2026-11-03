package com.springkit.agentguidebook.client

import com.springkit.agentguidebook.search.Factory
import com.springkit.agentguidebook.services.GuidelineSearchService

/**
 * 기존 공개 Search Factory 경로를 canonical 검색 Factory로 연결합니다.
 *
 * 실제 입력은 검색 호출 시점에 canonical 입력 경계에서 읽습니다.
 */
object GuidelineSearchFactory {
  /**
   * 파일 시스템 읽기 경계를 갖춘 검색 서비스를 생성합니다.
   *
   * @return 실제 프로젝트 파일과 Git 저장소 식별자를 읽는 검색 서비스
   */
  fun fileSystem(): GuidelineSearchService = Factory.fileSystem()
}
