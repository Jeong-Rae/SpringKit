package com.springkit.agentguidebook.errors

/** 지침 파일의 구조, 값 또는 식별자가 올바르지 않을 때 발생합니다. */
class GuidelineLoadException(
    message: String = "지침 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
