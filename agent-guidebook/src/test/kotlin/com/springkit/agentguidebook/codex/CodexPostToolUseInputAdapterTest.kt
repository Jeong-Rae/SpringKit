package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.models.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CodexPostToolUseInputAdapterTest :
    FeatureSpec({
      val adapter = CodexPostToolUseInputAdapter()

      feature("Codex PostToolUse 입력 해석") {
        scenario("실측 apply_patch 입력을 읽으면, 지침 제공 요청을 반환합니다.") {
          withApplyPatchFixture { input, root ->
            val request = adapter.read(input).shouldNotBeNull()

            request.sessionId shouldBe SessionId("<SESSION_ID>")
            request.cwd shouldBe root
            request.filepaths shouldContainExactly listOf(root.resolve("src/Probe.kt"))
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                IrrelevantEventScenario(
                    name = "다른 이벤트를 읽으면, 나머지 필드를 검증하지 않고 처리하지 않습니다.",
                    input =
                        hookInput(
                            hookEventName = JsonPrimitive("PreToolUse"),
                            sessionId = JsonPrimitive(7),
                            cwd = JsonPrimitive("relative"),
                            toolResponse = JsonPrimitive(false),
                        ),
                ),
                IrrelevantEventScenario(
                    name = "다른 도구를 읽으면, 나머지 필드를 검증하지 않고 처리하지 않습니다.",
                    input =
                        hookInput(
                            toolName = JsonPrimitive("Bash"),
                            sessionId = JsonPrimitive(7),
                            cwd = JsonPrimitive("relative"),
                            toolResponse = JsonPrimitive(false),
                        ),
                ),
            ),
        ) { scenario ->
          scenario.input.let { adapter.read(it) } shouldBe null
        }
      }

      feature("처리되지 않는 apply_patch 결과") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                FailedPatchScenario(
                    name = "종료 코드가 0이 아니면, 지침 제공 요청을 반환하지 않습니다.",
                    toolResponse =
                        "Exit code: 1\nOutput:\nSuccess. Updated the following files:\nM /tmp/Probe.kt\n",
                ),
                FailedPatchScenario(
                    name = "성공 변경 목록이 없는 실패 출력을 받으면, 지침 제공 요청을 반환하지 않습니다.",
                    toolResponse = "Exit code: 0\nOutput:\nPatch failed: context did not match.\n",
                ),
            ),
        ) { scenario ->
          adapter.read(hookInput(toolResponse = JsonPrimitive(scenario.toolResponse))) shouldBe null
        }
      }

      feature("Codex Hook JSON 객체 검증") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidJsonScenario(
                    name = "JSON 문법이 잘못되면, 입력 오류를 전달합니다.",
                    input = """{"hook_event_name":""",
                ),
                InvalidJsonScenario(
                    name = "최상위 값이 배열이면, 입력 오류를 전달합니다.",
                    input = "[]",
                ),
                InvalidJsonScenario(
                    name = "최상위 값이 null이면, 입력 오류를 전달합니다.",
                    input = "null",
                ),
                InvalidJsonScenario(
                    name = "최상위 값이 문자열이면, 입력 오류를 전달합니다.",
                    input = "\"text\"",
                ),
                InvalidJsonScenario(
                    name = "최상위 값이 숫자이면, 입력 오류를 전달합니다.",
                    input = "1",
                ),
            ),
        ) { scenario ->
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(scenario.input)
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidJsonScenario(
                    name = "hook_event_name이 없으면, 입력 오류를 전달합니다.",
                    input = hookInput(hookEventName = null),
                ),
                InvalidJsonScenario(
                    name = "hook_event_name이 문자열이 아니면, 입력 오류를 전달합니다.",
                    input = hookInput(hookEventName = JsonPrimitive(1)),
                ),
                InvalidJsonScenario(
                    name = "tool_name이 없으면, 입력 오류를 전달합니다.",
                    input = hookInput(toolName = null),
                ),
                InvalidJsonScenario(
                    name = "tool_name이 문자열이 아니면, 입력 오류를 전달합니다.",
                    input = hookInput(toolName = JsonPrimitive(true)),
                ),
            ),
        ) { scenario ->
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(scenario.input)
          }
        }
      }

      feature("대상 이벤트의 필수 입력 검증") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidJsonScenario(
                    name = "session_id가 없으면, 입력 오류를 전달합니다.",
                    input = hookInput(sessionId = null),
                ),
                InvalidJsonScenario(
                    name = "session_id가 문자열이 아니면, 입력 오류를 전달합니다.",
                    input = hookInput(sessionId = JsonPrimitive(7)),
                ),
                InvalidJsonScenario(
                    name = "cwd가 없으면, 입력 오류를 전달합니다.",
                    input = hookInput(cwd = null),
                ),
                InvalidJsonScenario(
                    name = "cwd가 문자열이 아니면, 입력 오류를 전달합니다.",
                    input = hookInput(cwd = JsonNull),
                ),
                InvalidJsonScenario(
                    name = "tool_response가 없으면, 입력 오류를 전달합니다.",
                    input = hookInput(toolResponse = null),
                ),
                InvalidJsonScenario(
                    name = "tool_response가 문자열이 아니면, 입력 오류를 전달합니다.",
                    input = hookInput(toolResponse = JsonPrimitive(false)),
                ),
            ),
        ) { scenario ->
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(scenario.input)
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidJsonScenario(
                    name = "session_id가 빈 문자열이면, 입력 오류를 전달합니다.",
                    input = hookInput(sessionId = JsonPrimitive("")),
                ),
                InvalidJsonScenario(
                    name = "session_id가 공백으로만 구성되면, 입력 오류를 전달합니다.",
                    input = hookInput(sessionId = JsonPrimitive(" \t\n")),
                ),
            ),
        ) { scenario ->
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(scenario.input)
          }
        }

        scenario("cwd가 상대 경로이면, 입력 오류를 전달합니다.") {
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(hookInput(cwd = JsonPrimitive("relative/project")))
          }
        }
      }
    })

private data class IrrelevantEventScenario(
    val name: String,
    val input: String,
)

private data class FailedPatchScenario(
    val name: String,
    val toolResponse: String,
)

private data class InvalidJsonScenario(
    val name: String,
    val input: String,
)

private fun hookInput(
    hookEventName: JsonElement? = JsonPrimitive("PostToolUse"),
    toolName: JsonElement? = JsonPrimitive("apply_patch"),
    sessionId: JsonElement? = JsonPrimitive("session-a"),
    cwd: JsonElement? = JsonPrimitive("/tmp"),
    toolResponse: JsonElement? =
        JsonPrimitive(
            "Exit code: 0\nOutput:\nSuccess. Updated the following files:\nM /tmp/Probe.kt\n"
        ),
): String =
    buildJsonObject {
          hookEventName?.let { put("hook_event_name", it) }
          toolName?.let { put("tool_name", it) }
          sessionId?.let { put("session_id", it) }
          cwd?.let { put("cwd", it) }
          toolResponse?.let { put("tool_response", it) }
          put("unknown_metadata", buildJsonObject { put("enabled", JsonPrimitive(true)) })
        }
        .toString()

private fun withApplyPatchFixture(block: (String, Path) -> Unit) {
  val root = Files.createTempDirectory("codex input contract ")

  try {
    val source = root.resolve("src/Probe.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "val probeValue = \"after\"\n")

    val input =
        checkNotNull(
                Thread.currentThread()
                    .contextClassLoader
                    .getResourceAsStream("codex-hook/post-tool-use-apply-patch.input.json")
            ) {
              "실측 apply_patch 입력 fixture를 찾을 수 없습니다."
            }
            .bufferedReader()
            .use { reader ->
              reader
                  .readText()
                  .replace("<PROBE_CWD>", root.toString())
                  .replace("<PROBE_ROOT>", root.toString())
            }

    block(input, root)
  } finally {
    root.toFile().deleteRecursively()
  }
}
