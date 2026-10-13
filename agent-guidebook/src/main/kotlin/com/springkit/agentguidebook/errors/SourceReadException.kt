package com.springkit.agentguidebook.errors

/** 검색 대상 소스 파일을 읽을 수 없을 때 발생합니다. */
class SourceReadException(
    message: String = "검색 대상 소스 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
