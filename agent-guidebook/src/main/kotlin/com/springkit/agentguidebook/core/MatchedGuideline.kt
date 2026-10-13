package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId

/** 검색 결과로 제공할 지침의 식별자와 본문입니다. */
data class MatchedGuideline(
    val id: GuidelineId,
    val content: String,
)
