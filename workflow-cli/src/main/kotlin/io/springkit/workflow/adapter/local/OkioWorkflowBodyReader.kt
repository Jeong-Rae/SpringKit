package io.springkit.workflow.adapter.local

import io.springkit.workflow.runtime.WorkflowBodyReader
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/** Okio FileSystem에서 본문 파일을 UTF-8 문자열로 읽는 local adapter입니다. */
class OkioWorkflowBodyReader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : WorkflowBodyReader {
  override fun read(path: String): String =
      fileSystem.source(path.toPath()).buffer().use { source -> source.readUtf8() }
}
