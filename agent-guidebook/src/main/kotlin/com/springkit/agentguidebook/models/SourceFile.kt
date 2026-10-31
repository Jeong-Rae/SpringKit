package com.springkit.agentguidebook.models

import java.nio.file.Path

/**
 * 지침 관련성을 평가할 소스 파일의 입력을 보유합니다.
 *
 * @property filepath 파일의 경로
 * @property content 소스 내용
 * @property language 소스에 연결할 언어 식별자
 */
data class SourceFile(
    val filepath: Path,
    val content: String,
    val language: Language,
)
