package io.springkit.workflow.adapter.local

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.CharacterCodingException
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem

class OkioWorkflowBodyReaderTest :
    FunSpec({
      test("Okio FileSystem에서 본문을 읽으면, UTF-8 내용을 반환합니다") {
        val fileSystem = FakeFileSystem()
        val path = "/workspace/pr.md".toPath()
        fileSystem.createDirectories(requireNotNull(path.parent))
        fileSystem.sink(path).buffer().use { it.writeUtf8("본문") }

        OkioWorkflowBodyReader(fileSystem).read(path.toString()) shouldBe "본문"
      }

      test("유효하지 않은 UTF-8 바이트가 있으면, 디코딩 오류를 반환합니다") {
        val fileSystem = FakeFileSystem()
        val path = "/workspace/pr.md".toPath()
        fileSystem.createDirectories(requireNotNull(path.parent))
        fileSystem.sink(path).buffer().use { it.write(byteArrayOf(0xC3.toByte(), 0x28)) }

        shouldThrow<CharacterCodingException> {
          OkioWorkflowBodyReader(fileSystem).read(path.toString())
        }
      }
    })
