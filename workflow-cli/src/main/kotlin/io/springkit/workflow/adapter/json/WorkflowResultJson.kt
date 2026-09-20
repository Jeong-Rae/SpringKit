package io.springkit.workflow.adapter.json

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureConflict
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Workflow 명령 출력과 영속 상태가 공유하는 JSON 설정입니다. */
@OptIn(ExperimentalSerializationApi::class)
object WorkflowJson {
  val format: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
    prettyPrint = false
    classDiscriminator = "type"
    namingStrategy = JsonNamingStrategy.SnakeCase
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
  workspace?.let {
    put("workspace", buildJsonObject { put("path", it.path.value) })
  }
  if (conflicts.isNotEmpty()) {
    put("conflicts", buildJsonArray { conflicts.forEach { add(it.toJsonObject()) } })
  }
}

/** 충돌 파일을 Workflow JSON의 공통 실패 구조로 변환합니다. */
fun FailureConflict.toJsonObject(): JsonObject = buildJsonObject {
  put("path", path)
  put("kind", kind)
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

/** 안정적인 `{type, data}` Workflow 출력 계약에 따라 결과를 인코딩합니다. */
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
