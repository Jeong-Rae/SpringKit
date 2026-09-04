package me.jeongrae.springkit.generation

import java.nio.file.Path

fun interface GenerationAction {
    fun execute(projectRoot: Path)
}
