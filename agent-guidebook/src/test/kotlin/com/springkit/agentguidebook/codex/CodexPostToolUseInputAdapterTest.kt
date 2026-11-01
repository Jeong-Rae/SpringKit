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

      feature("Codex apply_patch 변경 경로 해석") {
        scenario("A, M, D 경로가 섞여 있으면, 추가와 수정 경로만 입력 순서로 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val modified = createFile(cwd.resolve("src/Modified.kt"))
            val added = createFile(cwd.resolve("src/Added.kt"))
            createFile(cwd.resolve("src/Deleted.kt"))
            val toolResponse =
                successfulToolResponse(
                    "M src/Modified.kt",
                    "A src/Added.kt",
                    "D src/Deleted.kt",
                )

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(modified, added)
          }
        }

        scenario("상대 경로와 절대 경로를 읽으면, payload cwd 기준으로 절대 경로를 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("request working directory"))
            val relativeFile = createFile(cwd.resolve("src/Relative.kt"))
            val absoluteFile = createFile(root.resolve("outside/Absolute.kt"))
            val toolResponse =
                successfulToolResponse(
                    "M src/Relative.kt",
                    "A $absoluteFile",
                )

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.cwd shouldBe cwd
            request.filepaths shouldContainExactly listOf(relativeFile, absoluteFile)
          }
        }

        scenario("정규화한 경로가 중복되면, 처음 나타난 순서로 한 번만 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val firstFile = createFile(cwd.resolve("src/한글 규칙.kt"))
            val secondFile = createFile(cwd.resolve("src/Second.kt"))
            val toolResponse =
                successfulToolResponse(
                    "M src/../src/한글 규칙.kt",
                    "A src/한글 규칙.kt",
                    "M src/Second.kt",
                )

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(firstFile, secondFile)
          }
        }

        scenario("경로에 공백과 한글이 있으면, 파일 경로를 그대로 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val file = createFile(cwd.resolve("src/개발자 안내 문서.kt"))
            val toolResponse = successfulToolResponse("A src/개발자 안내 문서.kt")

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(file)
          }
        }

        scenario("파일과 디렉터리가 섞인 변경 목록에서는, 존재하는 파일만 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val existingFile = createFile(cwd.resolve("src/Existing.kt"))
            Files.createDirectories(cwd.resolve("src/Directory.kt"))
            val toolResponse =
                successfulToolResponse(
                    "M src/Existing.kt",
                    "M src/Directory.kt",
                    "A src/Missing.kt",
                    "A new-directory/Child.kt",
                )

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(existingFile)
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                EmptyPathResultScenario(
                    name = "성공 목록에 변경 경로가 없으면, 처리하지 않습니다.",
                    toolResponse = successfulToolResponse(),
                    createFiles = {},
                ),
                EmptyPathResultScenario(
                    name = "모든 변경 경로가 삭제 상태이면, 처리하지 않습니다.",
                    toolResponse = successfulToolResponse("D src/Deleted.kt"),
                    createFiles = { cwd -> createFile(cwd.resolve("src/Deleted.kt")) },
                ),
                EmptyPathResultScenario(
                    name = "모든 추가와 수정 경로가 현재 없으면, 처리하지 않습니다.",
                    toolResponse =
                        successfulToolResponse(
                            "A src/Missing.kt",
                            "M new-directory/Missing.kt",
                        ),
                    createFiles = {},
                ),
            ),
        ) { scenario ->
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            scenario.createFiles(cwd)

            adapter.read(
                hookInput(
                    cwd = JsonPrimitive(cwd.toString()),
                    toolResponse = JsonPrimitive(scenario.toolResponse),
                )
            ) shouldBe null
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidJsonScenario(
                    name = "성공 marker 아래 알 수 없는 줄이 있으면, 입력 오류를 전달합니다.",
                    input =
                        hookInput(
                            cwd = JsonPrimitive("/tmp"),
                            toolResponse =
                                JsonPrimitive(
                                    successfulToolResponse("M src/Valid.kt") +
                                        "Unexpected output line\n"
                                ),
                        ),
                ),
                InvalidJsonScenario(
                    name = "성공 marker 아래 경로가 비어 있으면, 입력 오류를 전달합니다.",
                    input =
                        hookInput(
                            cwd = JsonPrimitive("/tmp"),
                            toolResponse =
                                JsonPrimitive(
                                    "Exit code: 0\nOutput:\n" +
                                        "Success. Updated the following files:\nM \n"
                                ),
                        ),
                ),
            ),
        ) { scenario ->
          shouldThrow<InvalidCodexHookInputException> {
            adapter.read(scenario.input)
          }
        }

        scenario("envelope가 없는 원래 성공 출력을 읽으면, 변경 파일 경로를 반환합니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val file = createFile(cwd.resolve("src/Original.kt"))
            val toolResponse = "Success. Updated the following files:\nM src/Original.kt\n"

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(file)
          }
        }

        scenario("경로는 문자열 데이터로 읽고 tool_input patch는 변경 경로 조회에 쓰지 않습니다.") {
          withTemporaryProject { root ->
            val cwd = Files.createDirectories(root.resolve("payload cwd"))
            val markerName = "codex-hook-shell-marker-" + System.nanoTime()
            val marker = Path.of(System.getProperty("user.dir")).resolve(markerName)
            val literalPath = "src/\$(touch $markerName).kt"
            val outputFile = createFile(cwd.resolve(literalPath))
            val patchDecoy = createFile(cwd.resolve("src/PatchDecoy.kt"))
            val toolInput = buildJsonObject {
              put(
                  "command",
                  JsonPrimitive(
                      "*** Begin Patch\n" +
                          "*** Add File: src/PatchDecoy.kt\n" +
                          "+patch input is not a search source\n" +
                          "*** End Patch"
                  ),
              )
            }
            val toolResponse = successfulToolResponse("M $literalPath")

            val request =
                adapter
                    .read(
                        hookInput(
                            cwd = JsonPrimitive(cwd.toString()),
                            toolResponse = JsonPrimitive(toolResponse),
                            toolInput = toolInput,
                        )
                    )
                    .shouldNotBeNull()

            request.filepaths shouldContainExactly listOf(outputFile)
            Files.exists(patchDecoy) shouldBe true
            Files.exists(marker) shouldBe false
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

private data class EmptyPathResultScenario(
    val name: String,
    val toolResponse: String,
    val createFiles: (Path) -> Unit,
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
    toolInput: JsonElement? = null,
): String =
    buildJsonObject {
          hookEventName?.let { put("hook_event_name", it) }
          toolName?.let { put("tool_name", it) }
          sessionId?.let { put("session_id", it) }
          cwd?.let { put("cwd", it) }
          toolResponse?.let { put("tool_response", it) }
          toolInput?.let { put("tool_input", it) }
          put("unknown_metadata", buildJsonObject { put("enabled", JsonPrimitive(true)) })
        }
        .toString()

private fun successfulToolResponse(vararg changedPaths: String): String = buildString {
  append("Exit code: 0\n")
  append("Wall time: 0.1s\n")
  append("Output:\n")
  append("Success. Updated the following files:\n")
  changedPaths.forEach { path ->
    append(path)
    append('\n')
  }
}

private fun withTemporaryProject(block: (Path) -> Unit) {
  val root = Files.createTempDirectory("codex path contract ")

  try {
    block(root)
  } finally {
    root.toFile().deleteRecursively()
  }
}

private fun createFile(path: Path): Path {
  val normalized = path.toAbsolutePath().normalize()
  Files.createDirectories(normalized.parent)
  Files.writeString(normalized, "content\n")
  return normalized
}

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
