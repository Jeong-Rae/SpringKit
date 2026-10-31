package com.springkit.agentguidebook.errors

/**
 * 지침 파일을 검색 입력으로 사용할 수 없음을 나타냅니다.
 *
 * @param message 호출자에게 전달할 지침 로딩 실패 설명
 * @param cause 파일 읽기, YAML 해석 또는 필드 검증의 원인
 */
class GuidelineLoadException(
    message: String = "지침 파일을 읽을 수 없습니다.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
