package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

class CodexHookApplication(
    private val inputAdapter: CodexPostToolUseInputAdapter,
    private val presentGuidelines: (GuidelinePresentationRequest) -> List<MatchedGuideline>,
    private val outputAdapter: CodexPostToolUseOutputAdapter,
) {
  fun run(input: InputStream, output: OutputStream, error: OutputStream): Int {
    try {
      val hookInput = input.readBytes().toString(UTF_8)
      val request = inputAdapter.read(hookInput) ?: return 0
      val guidelines = presentGuidelines(request)
      val response = outputAdapter.render(guidelines) ?: return 0

      output.write(response.toByteArray(UTF_8))
      output.flush()
      return 0
    } catch (exception: InvalidCodexHookInputException) {
      writeDiagnostic(error, "Codex Hook 입력 오류: ${exceptionMessages(exception)}")
      return INVALID_INPUT_EXIT_CODE
    } catch (exception: Exception) {
      writeDiagnostic(error, "Agent Guidebook 실행 실패: ${exceptionMessages(exception)}")
      return EXECUTION_FAILURE_EXIT_CODE
    }
  }

  private fun writeDiagnostic(error: OutputStream, message: String) {
    try {
      error.write((message + "\n").toByteArray(UTF_8))
      error.flush()
    } catch (_: Exception) {
      // stderr를 쓸 수 없어도 실행 실패 종료 코드를 반환합니다.
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
