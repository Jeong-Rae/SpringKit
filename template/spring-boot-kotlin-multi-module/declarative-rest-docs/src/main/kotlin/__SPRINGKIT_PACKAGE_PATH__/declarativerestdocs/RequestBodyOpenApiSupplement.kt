package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

/** OpenAPI generator가 충분히 표현하지 못한 raw body와 binary multipart part를 보완합니다. */
internal object RequestBodyOpenApiSupplement {
  fun apply(
      document: Map<String, Any?>,
      metadata: List<RequestBodyOpenApiMetadata>,
  ): Map<String, Any?> {
    val result = mutableCopy(document).asObject("OpenAPI 문서")
    metadata.forEach { supplement -> result.apply(supplement) }
    return result
  }

  private fun MutableMap<String, Any?>.apply(metadata: RequestBodyOpenApiMetadata) {
    val paths = requireObject(this["paths"], "paths")
    val operation =
        paths.values
            .asSequence()
            .filterIsInstance<Map<*, *>>()
            .flatMap { it.values.asSequence() }
            .filterIsInstance<MutableMap<*, *>>()
            .map { it.asObject("operation") }
            .singleOrNull { it["operationId"] == metadata.operationId }
            ?: error("OpenAPI operationId를 찾을 수 없습니다: ${metadata.operationId}")

    @Suppress("UNCHECKED_CAST")
    var requestBody = operation["requestBody"] as? MutableMap<String, Any?>
    if (requestBody == null) {
      requestBody = linkedMapOf("required" to true)
      operation["requestBody"] = requestBody
    }

    @Suppress("UNCHECKED_CAST")
    val content =
        (requestBody["content"] as? MutableMap<String, Any?>)
            ?: linkedMapOf<String, Any?>().also { requestBody["content"] = it }
    val mediaType = metadata.contentType.mediaType()
    val contentKey =
        content.keys.firstOrNull { it.mediaType().equals(mediaType, ignoreCase = true) }
            ?: mediaType
    @Suppress("UNCHECKED_CAST")
    val media =
        (content[contentKey] as? MutableMap<String, Any?>)
            ?: linkedMapOf<String, Any?>().also { content[contentKey] = it }

    when (metadata.kind) {
      RequestBodyFormat.Kind.RAW.name -> {
        requestBody["required"] = !metadata.optional
        requestBody["description"] =
            requireNotNull(metadata.description) { "raw body 설명이 없습니다: ${metadata.operationId}" }
        media["schema"] = linkedMapOf("type" to "string", "format" to "binary")
        media.remove("example")
        media.remove("examples")
      }
      RequestBodyFormat.Kind.MULTIPART.name ->
          supplementMultipart(this, requestBody, media, metadata)
      else -> error("보완할 수 없는 본문 형식입니다: ${metadata.kind}")
    }
  }

  private fun supplementMultipart(
      root: MutableMap<String, Any?>,
      requestBody: MutableMap<String, Any?>,
      media: MutableMap<String, Any?>,
      metadata: RequestBodyOpenApiMetadata,
  ) {
    requestBody.putIfAbsent("required", true)
    val originalSchema = media["schema"] as? MutableMap<String, Any?> ?: linkedMapOf()
    val schema =
        if (originalSchema.containsKey("\$ref")) {
          val reference =
              originalSchema["\$ref"] as? String
                  ?: error("multipart schema reference 형식이 잘못되었습니다: ${metadata.operationId}")
          val schemaName = reference.substringAfterLast('/')
          val componentSchemas =
              requireObject(requireObject(root["components"], "components")["schemas"], "schemas")
          mutableCopy(requireObject(componentSchemas[schemaName], "schema $schemaName"))
              .asObject("schema $schemaName")
              .also { media["schema"] = it }
        } else {
          originalSchema
        }
    media["schema"] = schema
    val existingProperties = schema["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val binaryParts = metadata.binaryParts.toSet()
    val properties =
        linkedMapOf<String, Any?>().apply {
          metadata.resourceFields.forEach { field ->
            val existing = existingProperties[field.path] as? Map<*, *>
            put(
                field.path,
                if (field.path in binaryParts) {
                  linkedMapOf<String, Any?>("type" to "string", "format" to "binary").apply {
                    put("description", field.description)
                  }
                } else {
                  (existing?.let {
                        mutableCopy(it).asObject("${metadata.operationId} part ${field.path}")
                      } ?: schemaFor(field))
                      .apply {
                        put("description", field.description)
                        if (field.type.equals("INTEGER", ignoreCase = true)) {
                          put("type", "integer")
                        }
                      }
                },
            )
          }
        }
    schema["type"] = "object"
    schema["properties"] = properties
    schema["required"] = metadata.resourceFields.filterNot { it.optional }.map { it.path }
    @Suppress("UNCHECKED_CAST")
    val encoding = media["encoding"] as? MutableMap<String, Any?> ?: linkedMapOf()
    metadata.resourceFields.forEach { field ->
      encoding[field.path] = mapOf("contentType" to field.contentType)
    }
    media["encoding"] = encoding
  }

  private fun schemaFor(field: RequestBodyOpenApiField): MutableMap<String, Any?> {
    val fieldType = field.type.uppercase()
    val schema =
        linkedMapOf<String, Any?>(
            "type" to
                when (fieldType) {
                  "BOOLEAN" -> "boolean"
                  "NUMBER" -> "number"
                  "INTEGER" -> "integer"
                  "ARRAY" -> "array"
                  "OBJECT" -> "object"
                  else -> "string"
                }
        )
    schema["description"] = field.description
    if (fieldType == "ARRAY") {
      val itemType = field.attributes["itemsType"] as? String
      schema["items"] =
          mapOf(
              "type" to
                  when (itemType?.uppercase()) {
                    "BOOLEAN" -> "boolean"
                    "NUMBER" -> "number"
                    "INTEGER" -> "integer"
                    "OBJECT" -> "object"
                    else -> "string"
                  }
          )
    }
    val enumValues = field.attributes["enumValues"] as? List<*>
    if (enumValues != null) schema["enum"] = enumValues
    return schema
  }

  private fun String?.mediaType(): String =
      this?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
          ?: error("OpenAPI request body Content-Type가 없습니다.")

  private fun requireObject(value: Any?, label: String): MutableMap<String, Any?> =
      (value as? MutableMap<String, Any?>) ?: error("$label 항목이 객체가 아닙니다.")

  private fun Any?.asObject(label: String): MutableMap<String, Any?> = requireObject(this, label)

  private fun mutableCopy(value: Any?): Any? =
      when (value) {
        is Map<*, *> ->
            linkedMapOf<String, Any?>().apply {
              value.forEach { (key, item) ->
                put(key as? String ?: error("객체 key가 문자열이 아닙니다."), mutableCopy(item))
              }
            }
        is List<*> -> value.map(::mutableCopy).toMutableList()
        else -> value
      }
}

/** Snippet metadata consumed by the explicit OpenAPI generation task. */
internal data class RequestBodyOpenApiMetadata(
    val operationId: String,
    val kind: String,
    val contentType: String?,
    val description: String?,
    val optional: Boolean = false,
    val binaryParts: List<String>,
    val resourceFields: List<RequestBodyOpenApiField> = emptyList(),
)

internal data class RequestBodyOpenApiField(
    val path: String,
    val description: String,
    val type: String,
    val contentType: String,
    val optional: Boolean,
    val attributes: Map<String, Any?>,
)
