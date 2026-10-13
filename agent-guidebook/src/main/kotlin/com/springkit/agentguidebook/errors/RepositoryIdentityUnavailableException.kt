package com.springkit.agentguidebook.errors

/** 현재 저장소의 origin에서 저장소 식별자를 얻을 수 없을 때 발생합니다. */
class RepositoryIdentityUnavailableException(
    message: String = "저장소 식별자를 확인할 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
