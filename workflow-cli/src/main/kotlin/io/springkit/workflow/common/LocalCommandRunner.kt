package io.springkit.workflow.common

import java.nio.file.Path
import java.util.concurrent.Executors

/** The output of one process invocation. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** Runs an executable without introducing a shell or command-specific policy. */
fun interface CommandRunner {
  fun run(command: List<String>, workingDirectory: Path): CommandResult
}

/** A [CommandRunner] backed by [ProcessBuilder]. */
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
