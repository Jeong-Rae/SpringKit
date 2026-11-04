package com.springkit.agentguidebook.provision.codex

import com.springkit.agentguidebook.domain.MatchedGuideline
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class PostToolUseOutputAdapter {
  /** 빈 결과의 `null`은 렌더링 실패가 아니라 추가 Context 생략을 뜻합니다. [HookOutput.present]가 쓰기와 flush를 건너뜁니다. */
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
