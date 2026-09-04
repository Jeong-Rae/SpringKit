package me.jeongrae.springkit.kit.client.project

import java.nio.file.Files
import java.nio.file.Path
import me.jeongrae.springkit.kit.core.project.ProjectDirectoryCreator
import me.jeongrae.springkit.kit.core.project.ProjectRoot

class FileSystemProjectDirectoryCreator : ProjectDirectoryCreator {
    override fun create(outputPath: Path): ProjectRoot =
        ProjectRoot(Files.createDirectory(outputPath))
}
