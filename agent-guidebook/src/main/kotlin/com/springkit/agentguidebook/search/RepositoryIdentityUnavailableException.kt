package com.springkit.agentguidebook.search

class RepositoryIdentityUnavailableException(
    message: String = "저장소 식별자를 확인할 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
