package me.jeongrae.springkit.kit.core.project

import java.nio.file.Path

fun interface ProjectDirectoryCreator {
    fun create(outputPath: Path): ProjectRoot
}
