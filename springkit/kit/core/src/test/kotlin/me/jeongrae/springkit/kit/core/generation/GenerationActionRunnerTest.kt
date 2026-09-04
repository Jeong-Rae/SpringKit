package me.jeongrae.springkit.kit.core.generation

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import me.jeongrae.springkit.kit.core.project.ProjectRoot

class GenerationActionRunnerTest {
    @Test
    fun `동일한 프로젝트 루트로 생성 작업을 순서대로 실행한다`() {
        val projectRoot = ProjectRoot(Path("project"))
        val invocations = mutableListOf<Pair<String, ProjectRoot>>()
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
    fun `실행기를 만들 때 생성 작업 순서를 복사한다`() {
        val invocations = mutableListOf<String>()
        val actions = mutableListOf(GenerationAction { invocations += "first" })
        val runner = GenerationActionRunner(actions)
        actions += GenerationAction { invocations += "late" }

        runner.run(ProjectRoot(Path("project")))

        assertEquals(listOf("first"), invocations)
    }

    @Test
    fun `빈 생성 작업 목록을 허용한다`() {
        GenerationActionRunner(emptyList()).run(ProjectRoot(Path("project")))
    }

    @Test
    fun `실패를 전파하고 이후 생성 작업을 실행하지 않는다`() {
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
                runner.run(ProjectRoot(Path("project")))
            }

        assertSame(expectedFailure, actualFailure)
        assertEquals(false, laterActionExecuted)
    }
}
