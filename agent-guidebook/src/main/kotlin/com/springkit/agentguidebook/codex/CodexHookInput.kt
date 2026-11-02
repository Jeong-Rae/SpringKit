package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.io.InputStream

class CodexHookInput(
    private val input: InputStream,
    private val inputAdapter: CodexPostToolUseInputAdapter,
) {
  fun read(): GuidelinePresentationRequest? = throw NotImplementedError("CodexHookInput.read")
}
