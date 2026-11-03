package com.springkit.agentguidebook.presentation.codex

import com.springkit.agentguidebook.presentation.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HookInputTest :
    FeatureSpec({
      feature("Codex Hook 입력 포트") {
        scenario("나누어 읽히는 UTF-8 입력을 EOF까지 읽고 요청으로 변환하며 스트림을 닫지 않습니다.") {
          withTemporaryProject { cwd ->
            val source = createFile(cwd.resolve("src/한글 규칙.kt"))
            val stream = ChunkedInputStream(postToolUseInput(cwd).toByteArray(UTF_8), chunkSize = 2)

            val request = HookInput(stream, PostToolUseInputAdapter()).read().shouldNotBeNull()

            request.sessionId shouldBe SessionId("session-한글")
            request.cwd shouldBe cwd
            request.filepaths shouldContainExactly listOf(source)
            stream.closed shouldBe false
          }
        }

        scenario("무관한 이벤트이면 null을 반환하고 입력 스트림을 닫지 않습니다.") {
          val stream =
              ChunkedInputStream(
                  """{"hook_event_name":"PreToolUse","tool_name":"apply_patch"}"""
                      .toByteArray(UTF_8),
                  chunkSize = 5,
              )

          val request = HookInput(stream, PostToolUseInputAdapter()).read()

          request shouldBe null
          stream.closed shouldBe false
        }

        scenario("잘못된 JSON이면 입력 예외를 전달하고 스트림을 닫지 않습니다.") {
          val stream = ChunkedInputStream("{".toByteArray(UTF_8), chunkSize = 1)

          shouldThrow<InvalidHookInputException> {
            HookInput(stream, PostToolUseInputAdapter()).read()
          }
          stream.closed shouldBe false
        }

        scenario("입력 읽기 IOException을 전달하고 스트림을 닫지 않습니다.") {
          val failure = IOException("입력 스트림 실패")
          val stream = PortFailingInputStream(failure)

          shouldThrow<IOException> {
            HookInput(stream, PostToolUseInputAdapter()).read()
          }
          stream.closed shouldBe false
        }
      }
    })

private class ChunkedInputStream(
    bytes: ByteArray,
    private val chunkSize: Int,
) : ByteArrayInputStream(bytes) {
  var closed = false
    private set

  override fun read(
      target: ByteArray,
      offset: Int,
      length: Int,
  ): Int = super.read(target, offset, minOf(length, chunkSize))

  override fun close() {
    closed = true
    super.close()
  }
}

private class PortFailingInputStream(private val failure: IOException) : InputStream() {
  var closed = false
    private set

  override fun read(): Int = throw failure

  override fun close() {
    closed = true
  }
}

private fun postToolUseInput(cwd: Path): String =
    buildJsonObject {
          put("session_id", JsonPrimitive("session-한글"))
          put("cwd", JsonPrimitive(cwd.toString()))
          put("hook_event_name", JsonPrimitive("PostToolUse"))
          put("tool_name", JsonPrimitive("apply_patch"))
          put(
              "tool_response",
              JsonPrimitive(
                  "Exit code: 0\n" +
                      "Output:\n" +
                      "Success. Updated the following files:\n" +
                      "M src/한글 규칙.kt\n"
              ),
          )
        }
        .toString()

private fun withTemporaryProject(block: (Path) -> Unit) {
  val cwd = Files.createTempDirectory("codex input port ")

  try {
    block(cwd)
  } finally {
    cwd.toFile().deleteRecursively()
  }
}

private fun createFile(path: Path): Path {
  Files.createDirectories(path.parent)
  Files.writeString(path, "val 안내 = \"UTF-8\"\n")
  return path
}
