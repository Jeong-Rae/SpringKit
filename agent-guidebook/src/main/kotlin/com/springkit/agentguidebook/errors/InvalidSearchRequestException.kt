package com.springkit.agentguidebook.errors

/** 검색 경로가 유효하지 않거나 검색 요청 조건을 충족하지 않을 때 발생합니다. */
class InvalidSearchRequestException(
    message: String = "검색 요청이 올바르지 않습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
