package io.springkit.workflow.adapter.store

import io.springkit.workflow.adapter.json.WorkflowJson
import io.springkit.workflow.domain.WorkflowState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/*
 * 영속 Workflow 상태를 위한 JSON 코덱입니다.
 */
object WorkflowStateJsonCodec {
  const val CURRENT_SCHEMA_VERSION: Int = 1

  private val serializer = WorkflowState.serializer()

  fun encodeToString(state: WorkflowState): String =
      WorkflowJson.format.encodeToString(serializer, state)

  fun decode(json: String): WorkflowState = decodeSafely {
    val element = WorkflowJson.format.parseToJsonElement(json)
    WorkflowJson.format.decodeFromJsonElement(serializer, withoutLegacyFeatureFlagFields(element))
  }

  fun decode(json: ByteArray): WorkflowState = decode(json.toString(Charsets.UTF_8))

  fun encodeToJsonElement(state: WorkflowState): JsonElement =
      WorkflowJson.format.encodeToJsonElement(serializer, state)

  fun decode(json: JsonElement): WorkflowState = decodeSafely {
    WorkflowJson.format.decodeFromJsonElement(serializer, withoutLegacyFeatureFlagFields(json))
  }

  private fun withoutLegacyFeatureFlagFields(element: JsonElement): JsonElement {
    val root = element as? JsonObject ?: return element
    val fieldsByCollection =
        mapOf(
            "sub_tasks" to setOf("exposure", "feature_flag_id"),
            "pull_requests" to setOf("exposure", "feature_flag_id"),
            "releases" to setOf("feature_flag_id"),
        )
    return JsonObject(
        root.mapValues { (collection, values) ->
          val legacyFields = fieldsByCollection[collection] ?: return@mapValues values
          val records = values as? JsonObject ?: return@mapValues values
          JsonObject(
              records.mapValues { (_, record) ->
                val fields = record as? JsonObject ?: return@mapValues record
                JsonObject(fields.filterKeys { it !in legacyFields })
              }
          )
        }
    )
  }

  private fun decodeSafely(read: () -> WorkflowState): WorkflowState =
      try {
        read().also { state ->
          if (state.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw StateDecodeException(
                "unsupported workflow state schema version: ${state.schemaVersion}"
            )
          }
        }
      } catch (exception: StateDecodeException) {
        throw exception
      } catch (exception: SerializationException) {
        throw StateDecodeException("invalid workflow state JSON", exception)
      } catch (exception: IllegalArgumentException) {
        throw StateDecodeException(
            "invalid workflow state: ${exception.message ?: "invariant violation"}",
            exception,
        )
      }
}

/*
 * 직렬화된 상태가 잘못되었거나 Workflow 상태 불변식을 위반했음을 나타냅니다.
 */
class StateDecodeException(message: String, cause: Throwable? = null) :
    SerializationException(message) {
  init {
    cause?.let(::initCause)
  }
}
