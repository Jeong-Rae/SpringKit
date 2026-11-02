package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest

class CodexHookApplication(
    private val presentGuidelines: (GuidelinePresentationRequest) -> List<MatchedGuideline>,
) {
  fun run(input: CodexHookInput, output: CodexHookOutput): Int =
      throw NotImplementedError("CodexHookApplication.run")

  private fun exceptionMessages(exception: Exception): String =
      generateSequence<Throwable>(exception) { it.cause }
          .map { cause -> cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName }
          .distinct()
          .joinToString(": ")

  private companion object {
    const val INVALID_INPUT_EXIT_CODE = 64
    const val EXECUTION_FAILURE_EXIT_CODE = 1
  }
}
