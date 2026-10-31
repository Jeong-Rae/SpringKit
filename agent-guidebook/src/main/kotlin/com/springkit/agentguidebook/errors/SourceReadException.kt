package com.springkit.agentguidebook.errors

/**
 * 검색 대상 Kotlin 파일을 읽거나 해석할 수 없음을 나타냅니다.
 *
 * @param message 호출자에게 전달할 소스 읽기 실패 설명
 * @param cause 파일 확인, 읽기 또는 디코딩 과정에서 발생한 원인
 */
class SourceReadException(
    message: String = "검색 대상 소스 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
