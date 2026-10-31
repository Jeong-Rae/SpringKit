package com.springkit.agentguidebook.models

/**
 * 하나의 소스에 지침을 적용할 때 필요한 환경을 함께 전달합니다.
 *
 * @property repository 현재 프로젝트를 식별하는 owner와 repository
 * @property language 적용 대상 소스의 언어
 * @property event 지침 적용을 요청한 이벤트
 */
data class ApplicabilityContext(
    val repository: RepositoryIdentity,
    val language: Language,
    val event: GuidelineEvent,
)
