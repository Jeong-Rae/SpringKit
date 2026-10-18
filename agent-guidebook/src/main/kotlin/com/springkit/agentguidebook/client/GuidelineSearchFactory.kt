package com.springkit.agentguidebook.client

import com.springkit.agentguidebook.services.GuidelineSearchService
import com.springkit.agentguidebook.storage.FileSystemGuidelineSearchInputReader

/** 실제 파일 시스템에서 지침 검색을 수행할 서비스 조립 지점입니다. */
object GuidelineSearchFactory {
  /** 파일 시스템 읽기 경계를 사용하는 지침 검색 서비스를 반환합니다. */
  fun fileSystem(): GuidelineSearchService =
      GuidelineSearchService(FileSystemGuidelineSearchInputReader())
}
