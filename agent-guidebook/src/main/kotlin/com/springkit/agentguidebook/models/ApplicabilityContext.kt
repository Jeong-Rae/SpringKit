package com.springkit.agentguidebook.models

/** 지침의 저장소, 언어, 이벤트 적용 여부를 평가하는 입력입니다. */
data class ApplicabilityContext(
    val repository: RepositoryIdentity,
    val language: Language,
    val event: GuidelineEvent,
)
