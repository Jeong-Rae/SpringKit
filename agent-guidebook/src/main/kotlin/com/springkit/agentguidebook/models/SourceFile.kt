package com.springkit.agentguidebook.models

import java.nio.file.Path

/** 검색할 파일의 경로, 내용, 언어를 보유합니다. */
data class SourceFile(
    val filepath: Path,
    val content: String,
    val language: Language,
)
