package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.MatchedGuideline

fun interface SearchGuidelines {
  fun execute(request: SearchRequest): List<MatchedGuideline>
}
