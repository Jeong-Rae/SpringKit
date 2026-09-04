package me.jeongrae.springkit.project

import java.nio.file.Files
import me.jeongrae.springkit.kit.core.project.ProjectDirectoryCreator
import me.jeongrae.springkit.kit.core.project.ProjectRoot

val fileSystemProjectDirectoryCreator: ProjectDirectoryCreator =
    ProjectDirectoryCreator { outputPath -> ProjectRoot(Files.createDirectory(outputPath)) }
