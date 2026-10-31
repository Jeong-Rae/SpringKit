package com.springkit.agentguidebook.errors

/**
 * 검색 요청 경로를 수정해야 하거나 대상 조건을 충족하지 못했음을 나타냅니다.
 *
 * @param message 호출자에게 전달할 요청 실패 설명
 * @param cause 경로 확인 등 요청 검증 중 발생한 원인
 */
class InvalidSearchRequestException(
    message: String = "검색 요청이 올바르지 않습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
