package com.springkit.agentguidebook.errors

/**
 * 트랜잭션 실패 뒤 rollback도 실패해 저장 상태를 확인해야 할 때 던집니다.
 *
 * 원래 작업 실패는 `cause`로, rollback 실패는 억제 예외로 보존합니다.
 */
class PresentationTransactionRollbackException(
    message: String = "Presentation 트랜잭션을 rollback하지 못했습니다.",
    cause: Throwable,
) : PresentationStorageException(message, cause)
