package com.springkit.agentguidebook.codex

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexHookProcessIntegrationTest :
    FeatureSpec({
      val hookScript = Path.of(System.getProperty("user.dir")).resolve("hooks/post-tool-use.sh")
      val appExecutable =
          Path.of(
              checkNotNull(System.getProperty("agentGuidebook.applicationExecutable")) {
                "Gradle installDist 실행 파일 경로가 전달되지 않았습니다."
              }
          )

      feature("Codex Hook에서 배포 앱까지 연결") {
        scenario("새 Session에 지침을 제공하고 같은 Session은 제외하며 다른 Session에는 분리해 기록합니다.") {
          withTemporaryProject { cwd ->
            withTemporaryHome { userHome ->
              val databasePath = userHome.resolve(".adaptive-guidebook/adaptive-guidebook.db")

              val first =
                  executeHook(hookScript, appExecutable, cwd, userHome, hookInput(cwd, "session-a"))
              val repeated =
                  executeHook(hookScript, appExecutable, cwd, userHome, hookInput(cwd, "session-a"))
              val anotherSession =
                  executeHook(hookScript, appExecutable, cwd, userHome, hookInput(cwd, "session-b"))

              withClue(
                  listOf(
                          first.diagnostic("첫 번째 Session 호출"),
                          repeated.diagnostic("같은 Session 재호출"),
                          anotherSession.diagnostic("다른 Session 호출"),
                      )
                      .joinToString("\n\n")
              ) {
                first.exitCode shouldBe 0
                repeated.exitCode shouldBe 0
                anotherSession.exitCode shouldBe 0
                first.additionalContext() shouldBe "[process-rule]\n프로세스 통합 지침입니다.\n"
                repeated.stdout shouldBe ""
                repeated.stderr shouldBe ""
                anotherSession.additionalContext() shouldBe "[process-rule]\n프로세스 통합 지침입니다.\n"
                rows(databasePath) shouldContainExactly
                    listOf(
                        StoredPresentation("session-a", "process-rule"),
                        StoredPresentation("session-b", "process-rule"),
                    )
              }
            }
          }
        }

        scenario("삭제되거나 없는 변경 파일만 받으면 빈 stdout과 DB 미생성을 반환합니다.") {
          withTemporaryProject { cwd ->
            val deletedFile = cwd.resolve("src/Deleted.kt")
            Files.writeString(deletedFile, "class Deleted")
            Files.delete(deletedFile)
            withTemporaryHome { userHome ->
              val databasePath = userHome.resolve(".adaptive-guidebook/adaptive-guidebook.db")

              val result =
                  executeHook(
                      hookScript,
                      appExecutable,
                      cwd,
                      userHome,
                      hookInput(
                          cwd = cwd,
                          sessionId = "session-empty",
                          changes = listOf("D src/Deleted.kt", "M src/Missing.kt"),
                      ),
                  )

              withClue(result.diagnostic("빈 검색 대상 호출")) {
                result.exitCode shouldBe 0
                result.stdout shouldBe ""
                result.stderr shouldBe ""
                Files.exists(databasePath) shouldBe false
              }
            }
          }
        }

        scenario("malformed JSON이면 입력 진단과 64를 반환하고 DB를 만들지 않습니다.") {
          withTemporaryProject { cwd ->
            withTemporaryHome { userHome ->
              val databasePath = userHome.resolve(".adaptive-guidebook/adaptive-guidebook.db")

              val result =
                  executeHook(
                      hookScript,
                      appExecutable,
                      cwd,
                      userHome,
                      input = """{"hook_event_name":""",
                  )

              withClue(result.diagnostic("잘못된 JSON 호출")) {
                result.exitCode shouldBe 64
                result.stdout shouldBe ""
                result.stderr shouldContain "입력"
                Files.exists(databasePath) shouldBe false
              }
            }
          }
        }

        scenario("SQLite 경로가 디렉터리이면 저장소 진단과 1을 반환하고 Context를 출력하지 않습니다.") {
          withTemporaryProject { cwd ->
            withTemporaryHome { userHome ->
              val databasePath = userHome.resolve(".adaptive-guidebook/adaptive-guidebook.db")
              Files.createDirectories(databasePath)

              val result =
                  executeHook(
                      hookScript,
                      appExecutable,
                      cwd,
                      userHome,
                      hookInput(cwd, "session-db-error"),
                  )

              withClue(result.diagnostic("저장 실패 호출")) {
                result.exitCode shouldBe 1
                result.stdout shouldBe ""
                result.stderr shouldContain "저장소"
                Files.isDirectory(databasePath) shouldBe true
              }
            }
          }
        }
      }
    })

private data class CodexHookProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
  fun diagnostic(label: String): String =
      "$label: exitCode=$exitCode\nstdout:\n$stdout\nstderr:\n$stderr"

  fun additionalContext(): String {
    val response = Json.parseToJsonElement(stdout).jsonObject
    val hookSpecificOutput = response.getValue("hookSpecificOutput").jsonObject

    response.keys shouldBe setOf("hookSpecificOutput")
    hookSpecificOutput.keys shouldBe setOf("hookEventName", "additionalContext")
    hookSpecificOutput.getValue("hookEventName").jsonPrimitive.content shouldBe "PostToolUse"

    return hookSpecificOutput.getValue("additionalContext").jsonPrimitive.content
  }
}

private data class StoredPresentation(
    val sessionId: String,
    val guidelineId: String,
)

private fun hookInput(
    cwd: Path,
    sessionId: String,
    changes: List<String> = listOf("M src/Probe.kt"),
): String {
  val toolResponse = buildString {
    append("Exit code: 0\n")
    append("Output:\n")
    append("Success. Updated the following files:\n")
    changes.forEach { change ->
      append(change)
      append('\n')
    }
  }
  val input =
      buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
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

private fun executeHook(
    hookScript: Path,
    appExecutable: Path,
    cwd: Path,
    userHome: Path,
    input: String,
): CodexHookProcessResult {
  val outputDirectory = Files.createTempDirectory(cwd, "process output ")
  val inputFile = outputDirectory.resolve("hook input.json")
  val stdoutFile = outputDirectory.resolve("child stdout.txt")
  val stderrFile = outputDirectory.resolve("child stderr.txt")
  var child: Process? = null

  try {
    Files.writeString(inputFile, input, UTF_8)
    val processBuilder = ProcessBuilder("/usr/bin/bash", hookScript.toString())
    processBuilder.directory(cwd.toFile())
    processBuilder.redirectInput(inputFile.toFile())
    processBuilder.redirectOutput(stdoutFile.toFile())
    processBuilder.redirectError(stderrFile.toFile())
    processBuilder.environment()["AGENT_GUIDEBOOK_APP_EXECUTABLE"] = appExecutable.toString()
    processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
    processBuilder.environment()["JAVA_OPTS"] =
        "-Duser.home=\"" + userHome.toAbsolutePath().normalize() + "\""

    val startedChild = processBuilder.start()
    child = startedChild
    if (!startedChild.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      startedChild.destroyForcibly()
      startedChild.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      throw AssertionError(
          "Codex Hook 프로세스가 제한 시간 안에 끝나지 않았습니다.\n" +
              "stdout:\n" +
              readIfPresent(stdoutFile) +
              "\n" +
              "stderr:\n" +
              readIfPresent(stderrFile)
      )
    }

    return CodexHookProcessResult(
        exitCode = startedChild.exitValue(),
        stdout = readIfPresent(stdoutFile),
        stderr = readIfPresent(stderrFile),
    )
  } catch (failure: Exception) {
    child?.let(::stopProcess)
    throw AssertionError(
        "Codex Hook 프로세스 실행에 실패했습니다.\n" +
            "stdout:\n" +
            readIfPresent(stdoutFile) +
            "\n" +
            "stderr:\n" +
            readIfPresent(stderrFile),
        failure,
    )
  } finally {
    child?.let(::stopProcess)
    deleteTree(outputDirectory)
  }
}

private fun readIfPresent(filepath: Path): String =
    if (Files.exists(filepath)) Files.readString(filepath, UTF_8) else ""

private fun rows(databasePath: Path): List<StoredPresentation> {
  if (!Files.isRegularFile(databasePath)) {
    return emptyList()
  }

  val storedRows = ArrayList<StoredPresentation>()
  DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
    connection.createStatement().use { statement ->
      statement
          .executeQuery(
              "SELECT session_id, guideline_id FROM presentation ORDER BY session_id, guideline_id"
          )
          .use { resultSet ->
            while (resultSet.next()) {
              storedRows.add(
                  StoredPresentation(
                      sessionId = resultSet.getString("session_id"),
                      guidelineId = resultSet.getString("guideline_id"),
                  )
              )
            }
          }
    }
  }
  return storedRows
}

private fun withTemporaryProject(action: (Path) -> Unit) {
  val cwd = Files.createTempDirectory("codex hook integration project ")

  try {
    runGit(cwd, "init", "--quiet")
    runGit(cwd, "remote", "add", "origin", "https://host/acme/backend-order.git")
    val sourceFile = cwd.resolve("src/Probe.kt")
    Files.createDirectories(sourceFile.parent)
    Files.writeString(sourceFile, "class Probe")
    writeGuideline(cwd)
    action(cwd)
  } finally {
    deleteTree(cwd)
  }
}

private fun writeGuideline(cwd: Path) {
  val guideline =
      """
      id: process-rule
      scope:
        repositories:
          - '*'
        languages:
          - 'kotlin'
      when:
        - 'file_changed'
      match:
        any:
          - regex:
              pattern: 'Probe'
      content: |
        프로세스 통합 지침입니다.
      """
          .trimIndent() + "\n"
  val guidelineFile = cwd.resolve(".agents/guides/rules/guideline.yaml")
  Files.createDirectories(guidelineFile.parent)
  Files.writeString(guidelineFile, guideline, UTF_8)
}

private fun runGit(cwd: Path, vararg arguments: String) {
  val process =
      ProcessBuilder(listOf("git", "-C", cwd.toString()) + arguments)
          .redirectErrorStream(true)
          .start()
  val output = process.inputStream.bufferedReader(UTF_8).use { it.readText() }
  val exitCode = process.waitFor()

  check(exitCode == 0) { "테스트용 Git 명령이 실패했습니다: $output" }
}

private fun withTemporaryHome(action: (Path) -> Unit) {
  val userHome = Files.createTempDirectory("codex hook user home ")

  try {
    action(userHome)
  } finally {
    deleteTree(userHome)
  }
}

private fun stopProcess(process: Process) {
  if (process.isAlive) {
    process.destroyForcibly()
  }
  process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

private fun deleteTree(root: Path) {
  if (!Files.exists(root)) {
    return
  }

  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

private const val PROCESS_TIMEOUT_SECONDS = 45L
private const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L
