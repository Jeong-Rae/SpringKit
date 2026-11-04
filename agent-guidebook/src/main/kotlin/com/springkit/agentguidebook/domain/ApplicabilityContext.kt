package com.springkit.agentguidebook.domain

data class ApplicabilityContext(
    val repository: RepositoryIdentity,
    val language: Language,
    val event: GuidelineEvent,
)
