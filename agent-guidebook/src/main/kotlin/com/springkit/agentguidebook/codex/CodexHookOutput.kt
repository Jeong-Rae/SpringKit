package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import java.io.OutputStream

class CodexHookOutput(
    private val stdout: OutputStream,
    private val stderr: OutputStream,
    private val outputAdapter: CodexPostToolUseOutputAdapter,
) {
  fun present(guidelines: List<MatchedGuideline>): Unit =
      throw NotImplementedError("CodexHookOutput.present")

  fun diagnose(message: String): Unit = throw NotImplementedError("CodexHookOutput.diagnose")
}
