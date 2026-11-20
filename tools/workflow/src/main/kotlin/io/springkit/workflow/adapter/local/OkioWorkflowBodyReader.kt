package io.springkit.workflow.adapter.local

import io.springkit.workflow.runtime.WorkflowBodyReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/* 잘못된 UTF-8 입력은 대체 문자로 바꾸지 않고 디코딩 오류로 거부합니다. */
class OkioWorkflowBodyReader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : WorkflowBodyReader {
  override fun read(path: String): String =
      fileSystem.source(path.toPath()).buffer().use { source ->
        StandardCharsets.UTF_8.newDecoder()
            .decode(ByteBuffer.wrap(source.readByteArray()))
            .toString()
      }
}
