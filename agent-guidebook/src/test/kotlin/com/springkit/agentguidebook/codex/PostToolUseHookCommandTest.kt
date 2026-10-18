package com.springkit.agentguidebook.codex

import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PostToolUseHookCommandTest :
    FeatureSpec({
      feature("Codex Hook 앱 프로세스 전달") {
        scenario("정상 입력을 공백 경로의 앱에 전달하면, 입력과 한글 출력 및 종료 코드가 보존됩니다.") {
          val workingDirectory = Path.of(System.getProperty("user.dir"))
          val hookScript = workingDirectory.resolve("hooks/post-tool-use.sh")
          val input = readTestResource("codex-hook/post-tool-use-apply-patch.input.json")
          val expectedStdout = readTestResource("codex-hook/post-tool-use-context.response.json")
          val expectedStderr = "앱 진단: 정상 전달입니다.\n".toByteArray(UTF_8)
          val temporaryDirectory = Files.createTempDirectory("codex hook app test ")

          try {
            val appDirectory =
                Files.createDirectories(temporaryDirectory.resolve("app with spaces"))
            val appExecutable = appDirectory.resolve("capture app stub.sh")
            val hookInput = temporaryDirectory.resolve("hook input.json")
            val capturedInput = temporaryDirectory.resolve("captured input.json")
            val capturedWorkingDirectory = temporaryDirectory.resolve("captured cwd.txt")
            val appStdout = temporaryDirectory.resolve("app stdout.json")
            val appStderr = temporaryDirectory.resolve("app stderr.txt")
            val appStub = readTestResource("codex-hook/app-stub.sh")
            Files.write(appExecutable, appStub)
            appExecutable.toFile().setExecutable(true) shouldBe true
            Files.write(hookInput, input)
            Files.write(appStdout, expectedStdout)
            Files.write(appStderr, expectedStderr)

            val processBuilder = ProcessBuilder("/usr/bin/bash", hookScript.toString())
            processBuilder.directory(temporaryDirectory.toFile())
            processBuilder.environment()["AGENT_GUIDEBOOK_APP_EXECUTABLE"] =
                appExecutable.toString()
            processBuilder.environment()["APP_STUB_CAPTURE_PATH"] = capturedInput.toString()
            processBuilder.environment()["APP_STUB_CWD_PATH"] = capturedWorkingDirectory.toString()
            processBuilder.environment()["APP_STUB_STDOUT_PATH"] = appStdout.toString()
            processBuilder.environment()["APP_STUB_STDERR_PATH"] = appStderr.toString()
            processBuilder.environment()["APP_STUB_EXIT_CODE"] = "0"
            processBuilder.redirectInput(hookInput.toFile())
            val process = processBuilder.start()
            val stdout = process.inputStream.readBytes()
            val stderr = process.errorStream.readBytes()
            val exitCode = process.waitFor()

            exitCode shouldBe 0
            Files.readAllBytes(capturedInput).toList() shouldBe input.toList()
            Files.readAllBytes(capturedWorkingDirectory).toList() shouldBe
                "${temporaryDirectory.toAbsolutePath().normalize()}\n".toByteArray(UTF_8).toList()
            stdout.toList() shouldBe expectedStdout.toList()
            stderr.toList() shouldBe expectedStderr.toList()
          } finally {
            temporaryDirectory.toFile().deleteRecursively()
          }
        }
      }
    })

private fun readTestResource(path: String): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
          "테스트 리소스를 찾을 수 없습니다: $path"
        }
        .use { resource -> resource.readBytes() }
