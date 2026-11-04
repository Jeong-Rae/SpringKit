package com.springkit.agentguidebook.presentation.codex

import com.springkit.agentguidebook.presentation.Factory
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

fun main() {
  val input =
      HookInput(
          input = System.`in`,
          inputAdapter = PostToolUseInputAdapter(),
      )
  val output =
      HookOutput(
          stdout = FileOutputStream(FileDescriptor.out),
          stderr = FileOutputStream(FileDescriptor.err),
          outputAdapter = PostToolUseOutputAdapter(),
      )
  val application = HookApplication(Factory.fileSystem()::execute)

  exitProcess(application.run(input, output))
}
