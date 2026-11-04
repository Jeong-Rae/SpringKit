package com.springkit.agentguidebook.provision.codex

import com.springkit.agentguidebook.domain.MatchedGuideline
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class PostToolUseOutputAdapter {
  /**
   * 검색 결과가 비어 있으면 현재 호출에 추가할 Context가 없습니다. 이때 `null`은 렌더링 실패가 아니라 Hook 응답을 생략한다는 뜻입니다. 출력 포트가 쓰기와
   * flush를 건너뛰도록 [HookOutput.present]에 처리를 맡깁니다.
   */
  fun render(guidelines: List<MatchedGuideline>): String? {
    if (guidelines.isEmpty()) {
      return null
    }

    val context =
        guidelines.joinToString(separator = "\n\n") { guideline ->
          "[" + guideline.id.value + "]\n" + guideline.content
        }
    val response = buildJsonObject {
      put(
          "hookSpecificOutput",
          buildJsonObject {
            put("hookEventName", JsonPrimitive(POST_TOOL_USE_EVENT))
            put("additionalContext", JsonPrimitive(context))
          },
      )
    }

    return Json.encodeToString(JsonElement.serializer(), response)
  }

  private companion object {
    const val POST_TOOL_USE_EVENT = "PostToolUse"
  }
}
