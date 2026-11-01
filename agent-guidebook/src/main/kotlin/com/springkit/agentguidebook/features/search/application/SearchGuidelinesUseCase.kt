package com.springkit.agentguidebook.features.search.application

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import com.springkit.agentguidebook.services.GuidelineSearchInputReader

/** 프로젝트 입력을 읽어 관련 Guideline을 검색하는 UseCase입니다. */
class SearchGuidelinesUseCase(
    private val inputReader: GuidelineSearchInputReader,
) : SearchGuidelines {
  override fun execute(request: GuidelineSearchRequest): List<MatchedGuideline> =
      throw NotImplementedError("SearchGuidelinesUseCase 계약 구현 전")
}
