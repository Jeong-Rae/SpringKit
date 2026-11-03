package com.springkit.agentguidebook.features.search.application

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelineSearchRequest

/** 지침 검색을 수행하는 Application 계약입니다. */
fun interface SearchGuidelines {
  fun execute(request: GuidelineSearchRequest): List<MatchedGuideline>
}
