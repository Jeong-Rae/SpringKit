package com.springkit.agentguidebook.search

class SourceReadException(
    message: String = "검색 대상 소스 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
