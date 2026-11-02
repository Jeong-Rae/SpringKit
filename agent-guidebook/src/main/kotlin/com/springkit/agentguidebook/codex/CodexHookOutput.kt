package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

class CodexHookOutput(
    private val stdout: OutputStream,
    private val stderr: OutputStream,
    private val outputAdapter: CodexPostToolUseOutputAdapter,
) {
  fun present(guidelines: List<MatchedGuideline>) {
    val response = outputAdapter.render(guidelines) ?: return
    stdout.write(response.toByteArray(UTF_8))
    stdout.flush()
  }

  fun diagnose(message: String) {
    try {
      stderr.write((message + "\n").toByteArray(UTF_8))
      stderr.flush()
    } catch (_: Exception) {
      // stderr 쓰기 실패는 실행 결과를 바꾸지 않습니다.
    }
  }
}
