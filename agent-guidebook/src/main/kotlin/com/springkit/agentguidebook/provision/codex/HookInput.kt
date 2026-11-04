package com.springkit.agentguidebook.provision.codex

import com.springkit.agentguidebook.provision.ProvisionRequest
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

class HookInput(
    private val input: InputStream,
    private val inputAdapter: PostToolUseInputAdapter,
) {
  /** `null`은 처리할 Hook 요청이 없다는 뜻이며 입력 오류와 다릅니다. [HookApplication.run]이 성공 종료로 처리합니다. */
  fun read(): ProvisionRequest? = inputAdapter.read(input.readBytes().toString(UTF_8))
}
