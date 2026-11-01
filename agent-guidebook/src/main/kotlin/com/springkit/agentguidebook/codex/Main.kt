package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.client.GuidelinePresentationFactory
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

fun main() {
  val application =
      CodexHookApplication(
          inputAdapter = CodexPostToolUseInputAdapter(),
          presentGuidelines = GuidelinePresentationFactory.fileSystem()::present,
          outputAdapter = CodexPostToolUseOutputAdapter(),
      )

  val output = FileOutputStream(FileDescriptor.out)
  val error = FileOutputStream(FileDescriptor.err)

  exitProcess(application.run(System.`in`, output, error))
}
