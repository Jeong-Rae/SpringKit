package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelineSearchRequest

/** 프로젝트 파일에서 관련 지침을 검색하는 공개 API입니다. */
class GuidelineSearchService {

  fun search(request: GuidelineSearchRequest): List<MatchedGuideline> =
      throw NotImplementedError("지침 검색 서비스는 아직 구현되지 않았습니다.")
}
