package me.jeongrae.springkit

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SpringKitTest {
    @Test
    fun `exposes the application name`() {
        assertEquals("SpringKit", APPLICATION_NAME)
    }

    @Test
    fun `generates the fixed starter project`(@TempDir temporaryDirectory: Path) {
        val outputPath = temporaryDirectory.resolve("starter")

        val projectRoot = generateFixedProject(outputPath)

        assertEquals(outputPath, projectRoot)
        assertTrue(projectRoot.isDirectory())
        assertEquals(FIXED_README_CONTENT, projectRoot.resolve("README.md").readText())
    }

    @Test
    fun `does not overwrite an existing project root`(@TempDir temporaryDirectory: Path) {
        val outputPath = temporaryDirectory.resolve("starter").createDirectory()

        assertFailsWith<FileAlreadyExistsException> {
            generateFixedProject(outputPath)
        }
    }

    @Test
    fun `requires exactly one output path`() {
        assertFailsWith<IllegalArgumentException> {
            main(emptyArray())
        }
        assertFailsWith<IllegalArgumentException> {
            main(arrayOf("first", "second"))
        }
    }
}
