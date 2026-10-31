package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId

/**
 * 지침 검색 결과로 전달할 식별자와 본문입니다.
 *
 * @property id 검색된 지침의 식별자
 * @property content 검색된 지침의 전체 본문
 */
data class MatchedGuideline(
    val id: GuidelineId,
    val content: String,
)
