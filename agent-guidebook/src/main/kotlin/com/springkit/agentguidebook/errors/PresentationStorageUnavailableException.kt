package com.springkit.agentguidebook.errors

/**
 * 경로, 권한, 용량 등 환경 문제로 저장소를 사용할 수 없을 때 던집니다.
 *
 * 환경 문제를 복구한 뒤 요청을 다시 호출할 수 있습니다.
 */
class PresentationStorageUnavailableException(
    message: String = "Presentation 저장소를 사용할 수 없습니다.",
    cause: Throwable? = null,
) : PresentationStorageException(message, cause)
