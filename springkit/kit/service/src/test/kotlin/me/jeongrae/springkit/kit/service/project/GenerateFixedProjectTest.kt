package me.jeongrae.springkit.kit.service.project

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import me.jeongrae.springkit.kit.core.generation.GenerationAction
import me.jeongrae.springkit.kit.core.generation.GenerationActionRunner
import me.jeongrae.springkit.kit.core.project.ProjectDirectoryCreator
import me.jeongrae.springkit.kit.core.project.ProjectRoot

class GenerateFixedProjectTest {
    @Test
    fun `프로젝트 루트를 만든 뒤 생성 작업을 실행한다`() {
        val outputPath = Path("project")
        val projectRoot = ProjectRoot(outputPath)
        val invocations = mutableListOf<String>()
        val useCase =
            GenerateFixedProject(
                projectDirectoryCreator =
                    ProjectDirectoryCreator {
                        invocations += "create"
                        projectRoot
                    },
                generationActionRunner =
                    GenerationActionRunner(
                        listOf(
                            GenerationAction { invocations += "generate" },
                        ),
                    ),
            )

        val result = useCase(GenerateFixedProject.Command(outputPath))

        assertEquals(listOf("create", "generate"), invocations)
        assertEquals(GenerateFixedProject.Result(projectRoot), result)
    }
}
