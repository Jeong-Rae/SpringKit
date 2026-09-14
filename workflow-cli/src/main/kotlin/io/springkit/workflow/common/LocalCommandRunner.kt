package io.springkit.workflow.common

import java.nio.file.Path
import java.util.concurrent.Executors

/** 한 번의 프로세스 실행 결과입니다. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** 셸이나 명령별 정책을 추가하지 않고 실행 파일을 실행합니다. */
fun interface CommandRunner {
  fun run(command: List<String>, workingDirectory: Path): CommandResult
}

/** [ProcessBuilder]를 사용해 [CommandRunner]를 구현합니다. */
class LocalCommandRunner : CommandRunner {
  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    require(command.isNotEmpty()) { "command must not be empty" }

    val process =
        ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(false)
            .start()
    val executor = Executors.newFixedThreadPool(2)
    return try {
      val stdout =
          executor.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
      val stderr =
          executor.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
      val exitCode = process.waitFor()
      CommandResult(exitCode, stdout.get(), stderr.get())
    } finally {
      executor.shutdown()
    }
  }
}
