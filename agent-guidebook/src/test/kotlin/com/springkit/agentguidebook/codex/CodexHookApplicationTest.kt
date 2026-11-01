package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.PresentationStorageUnavailableException
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexHookApplicationTest :
    FeatureSpec({
      feature("Codex Hook 실행 앱") {
        scenario("지침을 찾으면 응답 JSON만 stdout에 쓰고 0과 빈 stderr를 반환합니다.") {
          withHookProject { cwd ->
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val guideline = MatchedGuideline(GuidelineId("service-rule"), "서비스 지침 원문입니다.")

            val exitCode =
                application { listOf(guideline) }
                    .run(
                        input = postToolUseInput(cwd).toByteArrayInputStream(),
                        output = stdout,
                        error = stderr,
                    )

            exitCode shouldBe 0
            stderr.toString(UTF_8) shouldBe ""
            val response = Json.parseToJsonElement(stdout.toString(UTF_8)).jsonObject
            val hookSpecificOutput = response.getValue("hookSpecificOutput").jsonObject
            response.keys shouldBe setOf("hookSpecificOutput")
            hookSpecificOutput.getValue("hookEventName").jsonPrimitive.content shouldBe
                "PostToolUse"
            hookSpecificOutput.getValue("additionalContext").jsonPrimitive.content shouldBe
                "[service-rule]\n서비스 지침 원문입니다."
          }
        }

        scenario("무관한 이벤트이면 UseCase를 실행하지 않고 빈 stdout과 0을 반환합니다.") {
          val stdout = ByteArrayOutputStream()
          val stderr = ByteArrayOutputStream()
          val input = """{"hook_event_name":"PreToolUse","tool_name":"apply_patch"}"""

          val exitCode =
              application {
                    throw IllegalStateException("무관한 이벤트에서 UseCase가 실행되었습니다.")
                  }
                  .run(
                      input = input.toByteArrayInputStream(),
                      output = stdout,
                      error = stderr,
                  )

          exitCode shouldBe 0
          stdout.toByteArray().toList() shouldBe byteArrayOf().toList()
          stderr.toByteArray().toList() shouldBe byteArrayOf().toList()
        }

        scenario("새로 제공할 지침이 없으면 빈 stdout과 0을 반환합니다.") {
          withHookProject { cwd ->
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val exitCode =
                application { emptyList() }
                    .run(
                        input = postToolUseInput(cwd).toByteArrayInputStream(),
                        output = stdout,
                        error = stderr,
                    )

            exitCode shouldBe 0
            stdout.toByteArray().toList() shouldBe byteArrayOf().toList()
            stderr.toByteArray().toList() shouldBe byteArrayOf().toList()
          }
        }

        scenario("잘못된 입력이면 UseCase를 실행하지 않고 진단과 64를 반환합니다.") {
          val stdout = ByteArrayOutputStream()
          val stderr = ByteArrayOutputStream()

          val exitCode =
              application {
                    throw IllegalStateException("잘못된 입력에서 UseCase가 실행되었습니다.")
                  }
                  .run(
                      input = "{\"hook_event_name\":".toByteArrayInputStream(),
                      output = stdout,
                      error = stderr,
                  )

          exitCode shouldBe 64
          stdout.toByteArray().toList() shouldBe byteArrayOf().toList()
          stderr.toString(UTF_8) shouldContain "입력"
        }

        scenario("검색에서 예외가 나면 원인 진단과 1을 반환하고 stdout을 비웁니다.") {
          withHookProject { cwd ->
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val exitCode =
                application {
                      throw IllegalStateException("검색 서비스 예외")
                    }
                    .run(
                        input = postToolUseInput(cwd).toByteArrayInputStream(),
                        output = stdout,
                        error = stderr,
                    )

            exitCode shouldBe 1
            stdout.toByteArray().toList() shouldBe byteArrayOf().toList()
            stderr.toString(UTF_8) shouldContain "검색 서비스 예외"
          }
        }

        scenario("저장에서 예외가 나면 저장소 진단과 1을 반환하고 stdout을 비웁니다.") {
          withHookProject { cwd ->
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val failure =
                PresentationStorageUnavailableException(cause = IOException("DB 경로가 디렉터리입니다."))

            val exitCode =
                application {
                      throw failure
                    }
                    .run(
                        input = postToolUseInput(cwd).toByteArrayInputStream(),
                        output = stdout,
                        error = stderr,
                    )

            exitCode shouldBe 1
            stdout.toByteArray().toList() shouldBe byteArrayOf().toList()
            stderr.toString(UTF_8) shouldContain "Presentation 저장소를 사용할 수 없습니다."
            stderr.toString(UTF_8) shouldContain "DB 경로가 디렉터리입니다."
          }
        }

        scenario("stdout 기록에서 IOException이 나면 1을 반환합니다.") {
          withHookProject { cwd ->
            val stderr = ByteArrayOutputStream()
            val guideline = MatchedGuideline(GuidelineId("service-rule"), "서비스 지침 원문입니다.")

            val exitCode =
                application { listOf(guideline) }
                    .run(
                        input = postToolUseInput(cwd).toByteArrayInputStream(),
                        output = FailingOutputStream(IOException("stdout 기록 실패")),
                        error = stderr,
                    )

            exitCode shouldBe 1
            stderr.toString(UTF_8) shouldContain "stdout 기록 실패"
          }
        }
      }
    })

private class FailingOutputStream(private val failure: IOException) : OutputStream() {
  override fun write(value: Int) {
    throw failure
  }
}

private fun application(
    presentGuidelines: (GuidelinePresentationRequest) -> List<MatchedGuideline>,
): CodexHookApplication =
    CodexHookApplication(
        inputAdapter = CodexPostToolUseInputAdapter(),
        presentGuidelines = presentGuidelines,
        outputAdapter = CodexPostToolUseOutputAdapter(),
    )

private fun postToolUseInput(cwd: Path): String {
  val toolResponse =
      "Exit code: 0\n" +
          "Output:\n" +
          "Success. Updated the following files:\n" +
          "M src/Probe.kt\n"
  val input =
      buildJsonObject {
            put("session_id", JsonPrimitive("session-a"))
            put("cwd", JsonPrimitive(cwd.toString()))
            put("hook_event_name", JsonPrimitive("PostToolUse"))
            put("tool_name", JsonPrimitive("apply_patch"))
            put("tool_response", JsonPrimitive(toolResponse))
          }
          .toString()
  val decodedToolResponse =
      Json.parseToJsonElement(input).jsonObject.getValue("tool_response").jsonPrimitive.content

  decodedToolResponse shouldBe toolResponse
  decodedToolResponse shouldContain "\n"
  decodedToolResponse shouldContain "Success. Updated the following files:"

  return input
}

private fun String.toByteArrayInputStream(): ByteArrayInputStream =
    ByteArrayInputStream(toByteArray(UTF_8))

private fun withHookProject(action: (Path) -> Unit) {
  val cwd = Files.createTempDirectory("codex hook application ")
  val sourceFile = cwd.resolve("src/Probe.kt")
  Files.createDirectories(sourceFile.parent)
  Files.writeString(sourceFile, "class Probe")

  try {
    action(cwd)
  } finally {
    cwd.toFile().deleteRecursively()
  }
}
