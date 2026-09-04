package me.jeongrae.springkit.project

import java.nio.file.Files
import java.nio.file.Path

fun interface ProjectDirectoryFactory {
    fun create(projectRoot: Path): Path
}

val defaultProjectDirectoryFactory: ProjectDirectoryFactory =
    ProjectDirectoryFactory { projectRoot -> Files.createDirectory(projectRoot) }
