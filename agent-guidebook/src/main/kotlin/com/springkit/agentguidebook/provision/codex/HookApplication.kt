package com.springkit.agentguidebook.provision.codex

import com.springkit.agentguidebook.domain.MatchedGuideline
import com.springkit.agentguidebook.provision.ProvisionRequest

class HookApplication(
    private val provideGuidelines: (ProvisionRequest) -> List<MatchedGuideline>,
) {
  fun run(
      input: HookInput,
      output: HookOutput,
  ): Int {
    try {
      val request = input.read() ?: return 0
      val guidelines = provideGuidelines(request)
      output.present(guidelines)
      return 0
    } catch (exception: InvalidHookInputException) {
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
