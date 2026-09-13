package io.springkit.workflow.adapter.json

class JsonDecodeException(message: String) : IllegalArgumentException(message)

fun JsonValue.requireObject(location: String = "JSON value"): JsonValue.Object =
    this as? JsonValue.Object ?: throw JsonDecodeException("$location must be an object")

fun JsonValue.requireArray(location: String = "JSON value"): JsonValue.Array =
    this as? JsonValue.Array ?: throw JsonDecodeException("$location must be an array")

fun JsonValue.requireString(location: String = "JSON value"): String =
    (this as? JsonValue.String)?.value ?: throw JsonDecodeException("$location must be a string")

fun JsonValue.requireBoolean(location: String = "JSON value"): Boolean =
    (this as? JsonValue.Boolean)?.value ?: throw JsonDecodeException("$location must be a boolean")

fun JsonValue.requireLong(location: String = "JSON value"): Long {
  val lexical =
      (this as? JsonValue.Number)?.value ?: throw JsonDecodeException("$location must be a number")
  return lexical.toLongOrNull() ?: throw JsonDecodeException("$location must be an integer")
}

operator fun JsonValue.Object.get(name: String): JsonValue? = fields[name]

fun JsonValue.Object.required(name: String): JsonValue =
    fields[name] ?: throw JsonDecodeException("JSON object member '$name' is required")

fun JsonValue.Object.string(name: String): String = required(name).requireString("'$name'")

fun JsonValue.Object.optionalString(name: String): String? =
    when (val value = fields[name]) {
      null,
      JsonValue.Null,
      -> null
      else -> value.requireString("'$name'")
    }

fun JsonValue.Object.boolean(name: String, default: Boolean? = null): Boolean {
  val value = fields[name]
  if (value == null && default != null) return default
  return (value ?: throw JsonDecodeException("JSON object member '$name' is required"))
      .requireBoolean("'$name'")
}

fun JsonValue.Object.long(name: String, default: Long? = null): Long {
  val value = fields[name]
  if (value == null && default != null) return default
  return (value ?: throw JsonDecodeException("JSON object member '$name' is required")).requireLong(
      "'$name'"
  )
}

fun JsonValue.Object.array(name: String): List<JsonValue> =
    required(name).requireArray("'$name'").values

inline fun <reified T : Enum<T>> JsonValue.Object.enum(name: String): T {
  val value = string(name)
  return enumValues<T>().firstOrNull { it.name == value }
      ?: throw JsonDecodeException("'$name' has an unknown enum value '$value'")
}

fun jsonObjectOfNotNull(vararg entries: Pair<String, JsonValue?>): JsonValue.Object =
    JsonValue.Object(entries.mapNotNull { (name, value) -> value?.let { name to it } }.toMap())

fun String.toJson(): JsonValue = JsonValue.String(this)

fun Boolean.toJson(): JsonValue = JsonValue.Boolean(this)

fun Number.toJson(): JsonValue = JsonValue.Number(toString())

fun <T> Iterable<T>.toJson(transform: (T) -> JsonValue): JsonValue = JsonValue.Array(map(transform))
