package me.jeongrae.springkit.kit.client.generation

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import me.jeongrae.springkit.kit.core.project.ProjectRoot
import org.junit.jupiter.api.io.TempDir

class ReadmeGenerationActionTest {
    @Test
    fun `고정 README 파일을 생성한다`(@TempDir temporaryDirectory: Path) {
        ReadmeGenerationAction().execute(ProjectRoot(temporaryDirectory))

        assertEquals(
            FIXED_README_CONTENT,
            temporaryDirectory.resolve("README.md").readText(),
        )
    }
}
