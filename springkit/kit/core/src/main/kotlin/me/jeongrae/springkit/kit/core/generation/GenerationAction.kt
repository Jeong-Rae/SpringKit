package me.jeongrae.springkit.kit.core.generation

import me.jeongrae.springkit.kit.core.project.ProjectRoot

fun interface GenerationAction {
    fun execute(projectRoot: ProjectRoot)
}
