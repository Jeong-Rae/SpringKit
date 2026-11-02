package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

class CodexHookInput(
    private val input: InputStream,
    private val inputAdapter: CodexPostToolUseInputAdapter,
) {
  fun read(): GuidelinePresentationRequest? = inputAdapter.read(input.readBytes().toString(UTF_8))
}
