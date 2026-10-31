package com.springkit.agentguidebook.errors

/**
 * 현재 Git 저장소의 origin을 식별자로 변환할 수 없음을 나타냅니다.
 *
 * @param message 호출자에게 전달할 저장소 식별 실패 설명
 * @param cause Git 저장소, 설정 또는 origin 주소 처리 중 발생한 원인
 */
class RepositoryIdentityUnavailableException(
    message: String = "저장소 식별자를 확인할 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
