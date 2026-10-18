package com.springkit.agentguidebook.errors

/**
 * 일시적인 저장소 잠금 오류로 요청을 다시 시도할 수 있을 때 던집니다.
 *
 * 잠금이 해소된 뒤 동일 요청을 재시도할 수 있습니다.
 */
class RetryablePresentationStorageException(
    message: String = "Presentation 저장소가 잠겨 있어 다시 시도해야 합니다.",
    cause: Throwable? = null,
) : PresentationStorageException(message, cause)
