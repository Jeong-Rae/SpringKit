package me.jeongrae.springkit.generation

import java.nio.file.Path

class GenerationActionRunner(actions: Iterable<GenerationAction>) {
    private val actions = actions.toList()

    fun run(projectRoot: Path) {
        actions.forEach { action -> action.execute(projectRoot) }
    }
}
