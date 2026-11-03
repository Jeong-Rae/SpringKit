package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest

class CodexHookApplication(
    private val presentGuidelines: (GuidelinePresentationRequest) -> List<MatchedGuideline>,
) {
  fun run(
      input: CodexHookInput,
      output: CodexHookOutput,
  ): Int {
    try {
      val request = input.read() ?: return 0
      val guidelines = presentGuidelines(request)
      output.present(guidelines)
      return 0
    } catch (exception: InvalidCodexHookInputException) {
      output.diagnose("Codex Hook 입력 오류: ${exceptionMessages(exception)}")
      return INVALID_INPUT_EXIT_CODE
    } catch (exception: Exception) {
      output.diagnose("Agent Guidebook 실행 실패: ${exceptionMessages(exception)}")
      return EXECUTION_FAILURE_EXIT_CODE
    }
  }

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
