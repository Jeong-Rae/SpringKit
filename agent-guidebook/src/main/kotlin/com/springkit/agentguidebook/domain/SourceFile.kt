package com.springkit.agentguidebook.domain

import java.nio.file.Path

data class SourceFile(
    val filepath: Path,
    val content: String,
    val language: Language,
)
