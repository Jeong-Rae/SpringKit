package com.springkit.agentguidebook.presentation.codex

import com.springkit.agentguidebook.presentation.PresentationRequest
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

class HookInput(
    private val input: InputStream,
    private val inputAdapter: PostToolUseInputAdapter,
) {
  /**
   * 다른 Hook 이벤트·도구이거나 [PostToolUseInputAdapter.read]가 요청을 만들지 않으면 검색 요청이 없습니다. 이때 `null`은 입력 오류가
   * 아니라 처리할 요청이 없다는 뜻입니다. 입력 포트는 종료 상태를 결정하지 않으므로 [HookApplication.run]이 `null`을 성공 종료로 처리합니다.
   */
  fun read(): PresentationRequest? = inputAdapter.read(input.readBytes().toString(UTF_8))
}
