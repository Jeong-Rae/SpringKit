package me.jeongrae.springkit.kit.client.project

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

class FileSystemProjectDirectoryCreatorTest {
    private val projectDirectoryCreator = FileSystemProjectDirectoryCreator()

    @Test
    fun `빈 출력 위치에 프로젝트 루트를 생성한다`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project")

        val createdProjectRoot = projectDirectoryCreator.create(projectRoot)

        assertEquals(projectRoot, createdProjectRoot.path)
        assertTrue(createdProjectRoot.path.exists())
    }

    @Test
    fun `기존 디렉터리를 거부한다`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project").createDirectory()

        assertFailsWith<FileAlreadyExistsException> {
            projectDirectoryCreator.create(projectRoot)
        }
    }

    @Test
    fun `기존 파일을 거부한다`(@TempDir temporaryDirectory: Path) {
        val projectRoot = temporaryDirectory.resolve("project").createFile()

        assertFailsWith<FileAlreadyExistsException> {
            projectDirectoryCreator.create(projectRoot)
        }
    }

    @Test
    fun `프로젝트 루트를 생성할 수 없으면 오류를 전파한다`(@TempDir temporaryDirectory: Path) {
        val parentFile = temporaryDirectory.resolve("parent").createFile()
        val projectRoot = parentFile.resolve("project")

        assertFailsWith<IOException> {
            projectDirectoryCreator.create(projectRoot)
        }
        assertTrue(Files.isRegularFile(parentFile))
    }
}
