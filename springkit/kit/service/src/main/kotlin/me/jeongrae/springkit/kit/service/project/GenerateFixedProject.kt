package me.jeongrae.springkit.kit.service.project

import java.nio.file.Path
import me.jeongrae.springkit.kit.core.generation.GenerationActionRunner
import me.jeongrae.springkit.kit.core.project.ProjectDirectoryCreator
import me.jeongrae.springkit.kit.core.project.ProjectRoot

class GenerateFixedProject(
    private val projectDirectoryCreator: ProjectDirectoryCreator,
    private val generationActionRunner: GenerationActionRunner,
) {
    operator fun invoke(command: Command): Result {
        val projectRoot = projectDirectoryCreator.create(command.outputPath)
        generationActionRunner.run(projectRoot)
        return Result(projectRoot)
    }

    data class Command(
        val outputPath: Path,
    )

    data class Result(
        val projectRoot: ProjectRoot,
    )
}
