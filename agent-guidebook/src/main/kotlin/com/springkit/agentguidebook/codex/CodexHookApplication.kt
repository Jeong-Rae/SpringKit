package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.io.InputStream
import java.io.OutputStream

class CodexHookApplication(
    private val inputAdapter: CodexPostToolUseInputAdapter,
    private val presentGuidelines: (GuidelinePresentationRequest) -> List<MatchedGuideline>,
    private val outputAdapter: CodexPostToolUseOutputAdapter,
) {
  fun run(input: InputStream, output: OutputStream, error: OutputStream): Int =
      throw NotImplementedError("CodexHookApplication.run 계약 구현 전")
}
