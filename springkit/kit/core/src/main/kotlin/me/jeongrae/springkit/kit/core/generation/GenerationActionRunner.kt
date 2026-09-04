package me.jeongrae.springkit.kit.core.generation

import me.jeongrae.springkit.kit.core.project.ProjectRoot

class GenerationActionRunner(actions: Iterable<GenerationAction>) {
    private val actions = actions.toList()

    fun run(projectRoot: ProjectRoot) {
        actions.forEach { action -> action.execute(projectRoot) }
    }
}
