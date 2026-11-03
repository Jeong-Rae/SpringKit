package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.client.GuidelinePresentationFactory
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

fun main() {
  val input =
      CodexHookInput(
          input = System.`in`,
          inputAdapter = CodexPostToolUseInputAdapter(),
      )
  val output =
      CodexHookOutput(
          stdout = FileOutputStream(FileDescriptor.out),
          stderr = FileOutputStream(FileDescriptor.err),
          outputAdapter = CodexPostToolUseOutputAdapter(),
      )
  val application = CodexHookApplication(GuidelinePresentationFactory.fileSystem()::present)

  exitProcess(application.run(input, output))
}
