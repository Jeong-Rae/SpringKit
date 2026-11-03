package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.MatchedGuideline

/** 지침 검색을 수행하는 Application 계약입니다. */
fun interface SearchGuidelines {
  fun execute(request: SearchRequest): List<MatchedGuideline>
}
