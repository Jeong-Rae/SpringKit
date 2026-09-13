package io.springkit.workflow.adapter.json

/** Deterministic JSON renderer. Object member names are rendered lexicographically. */
object JsonRenderer {
  fun render(value: JsonValue): kotlin.String = buildString { appendValue(value) }

  private fun StringBuilder.appendValue(value: JsonValue) {
    when (value) {
      is JsonValue.Object -> {
        append('{')
        value.fields.entries
            .sortedBy { it.key }
            .forEachIndexed { index, (name, member) ->
              if (index != 0) append(',')
              appendString(name)
              append(':')
              appendValue(member)
            }
        append('}')
      }
      is JsonValue.Array -> {
        append('[')
        value.values.forEachIndexed { index, member ->
          if (index != 0) append(',')
          appendValue(member)
        }
        append(']')
      }
      is JsonValue.String -> appendString(value.value)
      is JsonValue.Number -> append(value.value)
      is JsonValue.Boolean -> append(if (value.value) "true" else "false")
      JsonValue.Null -> append("null")
    }
  }

  private fun StringBuilder.appendString(value: kotlin.String) {
    append('"')
    var index = 0
    while (index < value.length) {
      val character = value[index]
      when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        in '\u0000'..'\u001f' -> {
          append("\\u")
          append(character.code.toString(16).padStart(4, '0'))
        }
        else -> {
          when {
            character.isHighSurrogate() -> {
              check(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                "A JSON string cannot contain an unpaired UTF-16 surrogate"
              }
              append(character)
              append(value[index + 1])
              index++
            }
            character.isLowSurrogate() -> {
              error("A JSON string cannot contain an unpaired UTF-16 surrogate")
            }
            else -> append(character)
          }
        }
      }
      index++
    }
    append('"')
  }
}

fun JsonValue.renderJson(): kotlin.String = JsonRenderer.render(this)
