package io.springkit.workflow.adapter.local

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
    })
