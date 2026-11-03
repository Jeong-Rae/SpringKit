package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

class CodexHookInput(
    private val input: InputStream,
    private val inputAdapter: CodexPostToolUseInputAdapter,
) {
  /**
   * 다른 Hook 이벤트·도구이거나 [CodexPostToolUseInputAdapter.read]가 요청을 만들지 않으면 검색 요청이 없습니다. 이때 `null`은 입력
   * 오류가 아니라 처리할 요청이 없다는 뜻입니다. 입력 포트는 종료 상태를 결정하지 않으므로 [CodexHookApplication.run]이 `null`을 성공 종료로
   * 처리합니다.
   */
  fun read(): GuidelinePresentationRequest? = inputAdapter.read(input.readBytes().toString(UTF_8))
}
