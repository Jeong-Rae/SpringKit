package com.springkit.agentguidebook.errors

/**
 * 저장된 Presentation 이력을 올바르게 읽거나 재구성할 수 없을 때 던집니다.
 *
 * 변환 원인은 `cause`로 보존합니다.
 */
class PresentationDatabaseCorruptException(
    message: String = "Presentation 데이터베이스가 손상되었습니다.",
    cause: Throwable? = null,
) : PresentationStorageException(message, cause)
