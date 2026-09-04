package me.jeongrae.springkit.generation

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class GenerationActionRunnerTest {
    @Test
    fun `executes actions in order with the same project root`() {
        val projectRoot = Path("project")
        val invocations = mutableListOf<Pair<String, Path>>()
        val runner =
            GenerationActionRunner(
                listOf(
                    GenerationAction { root -> invocations += "first" to root },
                    GenerationAction { root -> invocations += "second" to root },
                ),
            )

        runner.run(projectRoot)

        assertEquals(
            listOf("first" to projectRoot, "second" to projectRoot),
            invocations,
        )
    }

    @Test
    fun `copies the action order when the runner is created`() {
        val invocations = mutableListOf<String>()
        val actions = mutableListOf(GenerationAction { invocations += "first" })
        val runner = GenerationActionRunner(actions)
        actions += GenerationAction { invocations += "late" }

        runner.run(Path("project"))

        assertEquals(listOf("first"), invocations)
    }

    @Test
    fun `accepts an empty action list`() {
        GenerationActionRunner(emptyList()).run(Path("project"))
    }

    @Test
    fun `propagates a failure and skips later actions`() {
        val expectedFailure = IllegalStateException("generation failed")
        var laterActionExecuted = false
        val runner =
            GenerationActionRunner(
                listOf(
                    GenerationAction { throw expectedFailure },
                    GenerationAction { laterActionExecuted = true },
                ),
            )

        val actualFailure =
            assertFailsWith<IllegalStateException> {
                runner.run(Path("project"))
            }

        assertSame(expectedFailure, actualFailure)
        assertEquals(false, laterActionExecuted)
    }
}
