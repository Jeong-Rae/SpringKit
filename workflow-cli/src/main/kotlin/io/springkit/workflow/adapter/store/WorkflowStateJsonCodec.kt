package io.springkit.workflow.adapter.store

import io.springkit.workflow.adapter.json.WorkflowJson
import io.springkit.workflow.domain.WorkflowState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** JSON codec for the durable workflow state. */
object WorkflowStateJsonCodec {
  const val CURRENT_SCHEMA_VERSION: Int = 1

  private val serializer = WorkflowState.serializer()

  fun encodeToString(state: WorkflowState): String =
      WorkflowJson.format.encodeToString(serializer, state)

  fun decode(json: String): WorkflowState = decodeSafely {
    WorkflowJson.format.decodeFromString(serializer, json)
  }

  fun decode(json: ByteArray): WorkflowState = decode(json.toString(Charsets.UTF_8))

  fun encodeToJsonElement(state: WorkflowState): JsonElement =
      WorkflowJson.format.encodeToJsonElement(serializer, state)

  fun decode(json: JsonElement): WorkflowState = decodeSafely {
    WorkflowJson.format.decodeFromJsonElement(serializer, json)
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

/** Signals malformed serialized state or a violation of a workflow state invariant. */
class StateDecodeException(message: String, cause: Throwable? = null) :
    SerializationException(message) {
  init {
    cause?.let(::initCause)
  }
}
