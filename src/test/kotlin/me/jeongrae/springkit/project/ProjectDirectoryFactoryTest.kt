package me.jeongrae.springkit.project

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectDirectoryFactoryTest {
    @Test
    fun `creates a project root at an empty output location`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project")

        val createdProjectRoot = fileSystemProjectDirectoryCreator.create(projectRoot)

        assertEquals(projectRoot, createdProjectRoot.path)
        assertTrue(createdProjectRoot.path.exists())
    }

    @Test
    fun `rejects an existing directory`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project").createDirectory()

        assertFailsWith<FileAlreadyExistsException> {
            fileSystemProjectDirectoryCreator.create(projectRoot)
        }
    }

    @Test
    fun `rejects an existing file`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project").createFile()

        assertFailsWith<FileAlreadyExistsException> {
            fileSystemProjectDirectoryCreator.create(projectRoot)
        }
    }

    @Test
    fun `propagates an error when the project root cannot be created`(@TempDir temporaryDirectory: Path) {
        val parentFile = temporaryDirectory.resolve("parent").createFile()
        val projectRoot = parentFile.resolve("project")

        assertFailsWith<IOException> {
            fileSystemProjectDirectoryCreator.create(projectRoot)
        }
        assertTrue(Files.isRegularFile(parentFile))
    }
}
