package com.springkit.agentguidebook.search

class GuidelineLoadException(
    message: String = "지침 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
