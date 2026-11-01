package com.springkit.agentguidebook.codex

import com.springkit.agentguidebook.core.MatchedGuideline
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CodexPostToolUseOutputAdapter {
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
