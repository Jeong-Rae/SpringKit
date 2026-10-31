package com.springkit.agentguidebook.codex

import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PostToolUseHookCommandTest :
    FeatureSpec({
      val hookScript = Path.of(System.getProperty("user.dir")).resolve("hooks/post-tool-use.sh")

      feature("Codex Hook 앱 프로세스 전달") {
        scenario("정상 입력을 앱에 전달하면, 입력과 출력 및 현재 작업 경로가 보존됩니다.") {
          withHookProcess(hookScript) {
            val expectedStderr = "앱 진단: 정상 전달입니다.\n".toByteArray(UTF_8)

            val result = execute(appStderr = expectedStderr)

            result.shouldBeSuccessful(
                expectedInput = defaultInput,
                expectedStdout = contextResponse,
                expectedStderr = expectedStderr,
                expectedWorkingDirectory = directory,
            )
          }
        }

        scenario("앱 stdout이 비어 있고 종료 코드가 0이면, 빈 stdout과 종료 코드 0을 반환합니다.") {
          withHookProcess(hookScript) {
            val result = execute(appStdout = byteArrayOf(), appStderr = byteArrayOf())

            result.shouldBeSuccessful(
                expectedInput = defaultInput,
                expectedStdout = byteArrayOf(),
                expectedStderr = byteArrayOf(),
                expectedWorkingDirectory = directory,
            )
          }
        }

        scenario("앱이 종료 코드 37을 반환하면, stderr와 종료 코드가 그대로 전달됩니다.") {
          withHookProcess(hookScript) {
            val appStdout = "앱 결과: context 생성 실패\n".toByteArray(UTF_8)
            val appStderr = "앱 진단: 입력 해석 실패\n한글도 그대로 유지합니다.\n".toByteArray(UTF_8)

            val result = execute(appStdout = appStdout, appStderr = appStderr, appExitCode = 37)

            result.exitCode shouldBe 37
            result.stdout.toList() shouldBe appStdout.toList()
            result.stderr.toList() shouldBe appStderr.toList()
            result.capturedInput?.toList() shouldBe defaultInput.toList()
          }
        }
      }

      feature("Codex Hook 앱 경로 설정 검증") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                AppPathSettingScenario(
                    name = "앱 경로 환경 변수가 없으면, 한국어 진단과 64를 반환합니다.",
                    path = null,
                    diagnostic = "앱 실행 파일 경로가 설정되지 않았습니다.\n",
                ),
                AppPathSettingScenario(
                    name = "앱 경로가 빈 문자열이면, 한국어 진단과 64를 반환합니다.",
                    path = "",
                    diagnostic = "앱 실행 파일 경로가 설정되지 않았습니다.\n",
                ),
            ),
        ) { scenario ->
          withHookProcess(hookScript) {
            val result = execute(appPath = scenario.path)

            result.shouldBeHookError(
                expectedExitCode = 64,
                expectedDiagnostic = scenario.diagnostic,
            )
          }
        }

        scenario("앱 경로가 상대 경로이면, 한국어 진단과 64를 반환합니다.") {
          withHookProcess(hookScript) {
            val result = execute(appPath = "relative/app executable")

            result.shouldBeHookError(
                expectedExitCode = 64,
                expectedDiagnostic = "앱 실행 파일 경로는 절대 경로여야 합니다.\n",
            )
          }
        }

        scenario("절대 경로의 앱 파일이 없으면, 한국어 진단과 127을 반환합니다.") {
          withHookProcess(hookScript) {
            val missingApp = directory.resolve("missing app executable")

            val result = execute(appPath = missingApp.toString())

            result.shouldBeHookError(
                expectedExitCode = 127,
                expectedDiagnostic = "앱 실행 파일을 찾을 수 없습니다.\n",
            )
          }
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidAppTargetScenario(
                    name = "앱 경로가 디렉터리이면, 한국어 진단과 126을 반환합니다.",
                    target = InvalidAppTarget.DIRECTORY,
                ),
                InvalidAppTargetScenario(
                    name = "앱 파일을 실행할 수 없으면, 한국어 진단과 126을 반환합니다.",
                    target = InvalidAppTarget.NON_EXECUTABLE_FILE,
                ),
            ),
        ) { scenario ->
          withHookProcess(hookScript) {
            val appPath =
                when (scenario.target) {
                  InvalidAppTarget.DIRECTORY ->
                      Files.createDirectories(directory.resolve("app directory")).toString()
                  InvalidAppTarget.NON_EXECUTABLE_FILE -> {
                    val path = directory.resolve("non executable app")
                    Files.write(path, appStubBytes)
                    path.toFile().setExecutable(false, false)
                    Files.isExecutable(path) shouldBe false
                    path.toString()
                  }
                }

            val result = execute(appPath = appPath)

            result.shouldBeHookError(
                expectedExitCode = 126,
                expectedDiagnostic = "앱 실행 파일을 실행할 수 없습니다.\n",
            )
          }
        }
      }

      feature("Codex Hook 입력과 앱 경로의 셸 문자 처리") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                ShellInputScenario(
                    name = "입력 JSON에 $() 명령 치환 문자열이 있어도, 명령을 실행하지 않습니다.",
                    input =
                        """{"tool_input":{"command":"$(touch hook-input-dollar-injected)"}}"""
                            .toByteArray(UTF_8),
                    markerName = "hook-input-dollar-injected",
                ),
                ShellInputScenario(
                    name = "입력 JSON에 역따옴표 명령 문자열이 있어도, 명령을 실행하지 않습니다.",
                    input =
                        """{"tool_input":{"command":"`touch hook-input-backtick-injected`"}}"""
                            .toByteArray(UTF_8),
                    markerName = "hook-input-backtick-injected",
                ),
            ),
        ) { scenario ->
          withHookProcess(hookScript) {
            val result = execute(input = scenario.input)

            Files.exists(directory.resolve(scenario.markerName)) shouldBe false
            result.capturedInput?.toList() shouldBe scenario.input.toList()
            result.exitCode shouldBe 0
            result.stdout.toList() shouldBe contextResponse.toList()
          }
        }

        scenario("앱 경로에 셸 문자가 있어도, 해당 경로의 앱만 실행합니다.") {
          withHookProcess(hookScript) {
            val executable = createAppExecutable("capture$(touch hook-path-injected); app stub.sh")

            val result = execute(appPath = executable.toString())

            Files.exists(directory.resolve("hook-path-injected")) shouldBe false
            result.shouldBeSuccessful(
                expectedInput = defaultInput,
                expectedStdout = contextResponse,
                expectedStderr = byteArrayOf(),
                expectedWorkingDirectory = directory,
            )
          }
        }
      }
    })

private enum class InvalidAppTarget {
  DIRECTORY,
  NON_EXECUTABLE_FILE,
}

private data class AppPathSettingScenario(
    val name: String,
    val path: String?,
    val diagnostic: String,
)

private data class InvalidAppTargetScenario(
    val name: String,
    val target: InvalidAppTarget,
)

private data class ShellInputScenario(
    val name: String,
    val input: ByteArray,
    val markerName: String,
)

private data class HookProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val capturedInput: ByteArray?,
    val capturedWorkingDirectory: ByteArray?,
)

private class HookProcessFixture(private val hookScript: Path) : AutoCloseable {
  val directory: Path = Files.createTempDirectory("codex hook app test ")
  val defaultInput: ByteArray = readTestResource("codex-hook/post-tool-use-apply-patch.input.json")
  val contextResponse: ByteArray =
      readTestResource("codex-hook/post-tool-use-context.response.json")
  val appStubBytes: ByteArray = readTestResource("codex-hook/app-stub.sh")

  private val defaultAppExecutable = createAppExecutable("capture app stub.sh")
  private val hookInputFile = directory.resolve("hook input.json")
  private val capturedInputFile = directory.resolve("captured input.json")
  private val capturedWorkingDirectoryFile = directory.resolve("captured cwd.txt")
  private val appStdoutFile = directory.resolve("app stdout.bin")
  private val appStderrFile = directory.resolve("app stderr.bin")

  fun createAppExecutable(fileName: String): Path {
    val executable = directory.resolve(fileName)
    Files.write(executable, appStubBytes)
    executable.toFile().setExecutable(true) shouldBe true
    return executable
  }

  fun execute(
      appPath: String? = defaultAppExecutable.toString(),
      input: ByteArray = defaultInput,
      appStdout: ByteArray = contextResponse,
      appStderr: ByteArray = byteArrayOf(),
      appExitCode: Int = 0,
  ): HookProcessResult {
    Files.write(hookInputFile, input)
    Files.write(appStdoutFile, appStdout)
    Files.write(appStderrFile, appStderr)

    val processBuilder = ProcessBuilder("/usr/bin/bash", hookScript.toString())
    processBuilder.directory(directory.toFile())
    val environment = processBuilder.environment()
    if (appPath == null) {
      environment.remove("AGENT_GUIDEBOOK_APP_EXECUTABLE")
    } else {
      environment["AGENT_GUIDEBOOK_APP_EXECUTABLE"] = appPath
    }
    environment["APP_STUB_CAPTURE_PATH"] = capturedInputFile.toString()
    environment["APP_STUB_CWD_PATH"] = capturedWorkingDirectoryFile.toString()
    environment["APP_STUB_STDOUT_PATH"] = appStdoutFile.toString()
    environment["APP_STUB_STDERR_PATH"] = appStderrFile.toString()
    environment["APP_STUB_EXIT_CODE"] = appExitCode.toString()
    processBuilder.redirectInput(hookInputFile.toFile())

    val process = processBuilder.start()
    val stdout = process.inputStream.readBytes()
    val stderr = process.errorStream.readBytes()
    val exitCode = process.waitFor()

    return HookProcessResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        capturedInput = capturedInputFile.readIfExists(),
        capturedWorkingDirectory = capturedWorkingDirectoryFile.readIfExists(),
    )
  }

  override fun close() {
    directory.toFile().deleteRecursively()
  }
}

private fun HookProcessResult.shouldBeSuccessful(
    expectedInput: ByteArray,
    expectedStdout: ByteArray,
    expectedStderr: ByteArray,
    expectedWorkingDirectory: Path,
) {
  exitCode shouldBe 0
  capturedInput?.toList() shouldBe expectedInput.toList()
  capturedWorkingDirectory?.toList() shouldBe
      "${expectedWorkingDirectory.toAbsolutePath().normalize()}\n".toByteArray(UTF_8).toList()
  stdout.toList() shouldBe expectedStdout.toList()
  stderr.toList() shouldBe expectedStderr.toList()
}

private fun HookProcessResult.shouldBeHookError(expectedExitCode: Int, expectedDiagnostic: String) {
  exitCode shouldBe expectedExitCode
  stdout.toList() shouldBe byteArrayOf().toList()
  stderr.toList() shouldBe expectedDiagnostic.toByteArray(UTF_8).toList()
  capturedInput shouldBe null
  capturedWorkingDirectory shouldBe null
}

private fun Path.readIfExists(): ByteArray? =
    if (Files.exists(this)) Files.readAllBytes(this) else null

private fun withHookProcess(hookScript: Path, block: HookProcessFixture.() -> Unit) {
  val fixture = HookProcessFixture(hookScript)

  try {
    fixture.block()
  } finally {
    fixture.close()
  }
}

private fun readTestResource(path: String): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
          "테스트 리소스를 찾을 수 없습니다: $path"
        }
        .use { resource -> resource.readBytes() }
