package io.springkit.workflow.adapter.json

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON settings shared by workflow command output and durable state. */
object WorkflowJson {
  val format: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
    prettyPrint = false
    classDiscriminator = "type"
  }
}

fun NextAction.toJsonObject(): JsonObject = buildJsonObject {
  put("action", action)
  put("actor", actor.name.lowercase())
  command?.let { put("command", it) }
}

fun BlockedBy.toJsonObject(): JsonObject = buildJsonObject {
  put("code", code)
  put("message", message)
  target?.let { put("target", it) }
}

fun FailureData.toJsonObject(): JsonObject = buildJsonObject {
  put("blocked_by", buildJsonArray { blockedBy.forEach { add(it.toJsonObject()) } })
  put("code", code.name)
  put("message", message)
  put("next", buildJsonArray { next.forEach { add(it.toJsonObject()) } })
}

fun WorkflowResult<JsonObject>.toJsonObject(): JsonObject =
    when (this) {
      is WorkflowResult.Failure ->
          buildJsonObject {
            put("data", data.toJsonObject())
            put("type", "failure")
          }
      is WorkflowResult.Success ->
          buildJsonObject {
            put("data", data.withNext(next))
            put("type", "success")
          }
    }

/** Encodes a result using the stable `{type, data}` workflow output contract. */
fun WorkflowResult<JsonObject>.encodeToString(): String =
    WorkflowJson.format.encodeToString(JsonObject.serializer(), toJsonObject())

private fun JsonObject.withNext(next: List<NextAction>): JsonObject {
  val fields = toMutableMap()
  if (next.isNotEmpty()) {
    fields["next"] = buildJsonArray { next.forEach { add(it.toJsonObject()) } }
  }
  return buildJsonObject {
    fields.toSortedMap().forEach { (name, value) -> put(name, value) }
  }
}
