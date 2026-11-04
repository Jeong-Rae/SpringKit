package com.springkit.agentguidebook.search

class InvalidSearchRequestException(
    message: String = "검색 요청이 올바르지 않습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
