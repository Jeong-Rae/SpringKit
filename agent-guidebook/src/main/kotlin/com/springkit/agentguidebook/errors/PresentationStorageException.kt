package com.springkit.agentguidebook.errors

/**
 * Presentation 영속성 경계에서 처리할 수 없는 오류가 발생할 때 던집니다.
 *
 * 확인되지 않은 영속성 실패의 기본 예외 타입입니다.
 */
open class PresentationStorageException(
    message: String = "Presentation 저장소 오류가 발생했습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
