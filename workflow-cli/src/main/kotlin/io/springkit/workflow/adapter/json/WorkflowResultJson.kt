package io.springkit.workflow.adapter.json

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult

fun NextAction.toJsonValue(): JsonValue.Object =
    jsonObjectOfNotNull(
        "actor" to actor.name.lowercase().toJson(),
        "action" to action.toJson(),
        "command" to command?.toJson(),
    )

fun BlockedBy.toJsonValue(): JsonValue.Object =
    jsonObjectOfNotNull(
        "code" to code.toJson(),
        "message" to message.toJson(),
        "target" to target?.toJson(),
    )

fun FailureData.toJsonValue(): JsonValue.Object =
    JsonValue.Object.of(
        "code" to code.name.toJson(),
        "message" to message.toJson(),
        "blocked_by" to blockedBy.toJson(BlockedBy::toJsonValue),
        "next" to next.toJson(NextAction::toJsonValue),
    )

fun WorkflowResult<JsonValue.Object>.toJsonValue(): JsonValue.Object =
    when (this) {
      is WorkflowResult.Failure ->
          JsonValue.Object.of(
              "type" to "failure".toJson(),
              "data" to data.toJsonValue(),
          )
      is WorkflowResult.Success -> {
        val resultData = LinkedHashMap(data.fields)
        if (next.isNotEmpty()) resultData["next"] = next.toJson(NextAction::toJsonValue)
        JsonValue.Object.of(
            "type" to "success".toJson(),
            "data" to JsonValue.Object(resultData),
        )
      }
    }

fun ActorKind.jsonName(): String = name.lowercase()
