package io.springkit.workflow.adapter.json

/**
 * The JSON value model used by the workflow adapters.
 *
 * This model deliberately contains no reflection or platform-specific serialization hooks. Objects
 * are copied at construction time, so a value cannot be changed by mutating the map or list
 * supplied by its caller.
 */
sealed interface JsonValue {
  /** A JSON object. Object member names are unique. */
  class Object(fields: Map<kotlin.String, JsonValue>) : JsonValue {
    val fields: Map<kotlin.String, JsonValue> = LinkedHashMap(fields)

    init {
      require(this.fields.size == fields.size) {
        "A JSON object cannot contain duplicate member names"
      }
    }

    override fun equals(other: Any?): kotlin.Boolean = other is Object && fields == other.fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): kotlin.String = JsonRenderer.render(this)

    companion object {
      fun of(vararg entries: Pair<kotlin.String, JsonValue>): Object {
        val result = LinkedHashMap<kotlin.String, JsonValue>(entries.size)
        entries.forEach { (name, value) ->
          require(result.put(name, value) == null) {
            "A JSON object cannot contain duplicate member name '$name'"
          }
        }
        return Object(result)
      }
    }
  }

  /** A JSON array. */
  class Array(values: List<JsonValue>) : JsonValue {
    val values: List<JsonValue> = values.toList()

    override fun equals(other: Any?): kotlin.Boolean = other is Array && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): kotlin.String = JsonRenderer.render(this)

    companion object {
      fun of(vararg values: JsonValue): Array = Array(values.toList())
    }
  }

  /** A JSON string. */
  data class String(val value: kotlin.String) : JsonValue {
    init {
      require(!value.hasUnpairedSurrogate()) {
        "A JSON string cannot contain an unpaired UTF-16 surrogate"
      }
    }

    override fun toString(): kotlin.String = JsonRenderer.render(this)
  }

  /** A JSON number represented by its validated JSON lexical form. */
  data class Number(val value: kotlin.String) : JsonValue {
    init {
      require(JsonNumberGrammar.isValid(value)) {
        "Invalid JSON number: '$value'"
      }
    }

    override fun toString(): kotlin.String = value
  }

  /** A JSON boolean. */
  data class Boolean(val value: kotlin.Boolean) : JsonValue {
    override fun toString(): kotlin.String = if (value) "true" else "false"
  }

  /** The single JSON null value. */
  object Null : JsonValue {
    override fun toString(): kotlin.String = "null"
  }

  companion object {
    fun parse(input: kotlin.String): JsonValue = JsonParser.parse(input)

    fun parse(input: ByteArray): JsonValue = JsonParser.parse(input)

    fun render(value: JsonValue): kotlin.String = JsonRenderer.render(value)

    fun obj(vararg entries: Pair<kotlin.String, JsonValue>): Object = Object.of(*entries)

    fun array(vararg values: JsonValue): Array = Array.of(*values)

    fun string(value: kotlin.String): String = String(value)

    fun number(value: kotlin.String): Number = Number(value)

    fun bool(value: kotlin.Boolean): Boolean = Boolean(value)
  }
}

typealias JsonObject = JsonValue.Object

typealias JsonArray = JsonValue.Array

typealias JsonString = JsonValue.String

typealias JsonNumber = JsonValue.Number

typealias JsonBoolean = JsonValue.Boolean

internal object JsonNumberGrammar {
  fun isValid(value: kotlin.String): kotlin.Boolean {
    if (value.isEmpty()) return false
    var index = 0
    if (value[index] == '-') {
      index++
      if (index == value.length) return false
    }

    if (value[index] == '0') {
      index++
      if (index < value.length && value[index].isDigit()) return false
    } else {
      if (index >= value.length || value[index] !in '1'..'9') return false
      while (index < value.length && value[index] in '0'..'9') index++
    }

    if (index < value.length && value[index] == '.') {
      index++
      val fractionStart = index
      while (index < value.length && value[index] in '0'..'9') index++
      if (fractionStart == index) return false
    }

    if (index < value.length && (value[index] == 'e' || value[index] == 'E')) {
      index++
      if (index < value.length && (value[index] == '+' || value[index] == '-')) index++
      val exponentStart = index
      while (index < value.length && value[index] in '0'..'9') index++
      if (exponentStart == index) return false
    }

    return index == value.length
  }
}

internal fun kotlin.String.hasUnpairedSurrogate(): kotlin.Boolean {
  var index = 0
  while (index < length) {
    val character = this[index]
    when {
      character.isHighSurrogate() -> {
        if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
        index += 2
      }
      character.isLowSurrogate() -> return true
      else -> index++
    }
  }
  return false
}
