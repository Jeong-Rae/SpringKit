package me.jeongrae.springkit

import kotlin.io.path.Path
import me.jeongrae.springkit.kit.client.generation.ReadmeGenerationAction
import me.jeongrae.springkit.kit.client.project.FileSystemProjectDirectoryCreator
import me.jeongrae.springkit.kit.core.generation.GenerationActionRunner
import me.jeongrae.springkit.kit.service.project.GenerateFixedProject

internal const val APPLICATION_NAME = "SpringKit"

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: springkit <output-path>" }

    val generateFixedProject =
        GenerateFixedProject(
            projectDirectoryCreator = FileSystemProjectDirectoryCreator(),
            generationActionRunner = GenerationActionRunner(listOf(ReadmeGenerationAction())),
        )
    val result = generateFixedProject(GenerateFixedProject.Command(Path(args.single())))

    println("$APPLICATION_NAME generated a project at ${result.projectRoot.path}")
}
