package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

/** 요청 본문을 JSON, raw bytes, multipart 중 하나로 분류한 결과 */
internal data class RequestBodyFormat(
    val kind: Kind,
    val contentType: String?,
    val fields: List<Field>,
) {
  enum class Kind {
    NONE,
    JSON,
    RAW,
    MULTIPART,
  }

  val rawField: Field?
    get() = fields.singleOrNull()?.takeIf { kind == Kind.RAW }

  val effectiveContentType: String?
    get() =
        contentType
            ?: when (kind) {
              Kind.JSON -> "application/json"
              Kind.RAW -> "application/octet-stream"
              Kind.MULTIPART -> "multipart/form-data"
              Kind.NONE -> null
            }
}

/** request Content-Type과 직접 선언한 field sample을 기준으로 본문 표현을 결정합니다. */
internal class RequestBodyFormatResolver {
  fun resolve(
      body: Body,
      requestHeaders: Headers,
  ): RequestBodyFormat {
    val contentType =
        requestHeaders.headers
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.sample
            ?.value
            ?.toString()

    if (body.fields.isEmpty()) {
      return RequestBodyFormat(RequestBodyFormat.Kind.NONE, contentType, emptyList())
    }

    if (
        contentType?.substringBefore(';')?.trim()?.startsWith("multipart/", ignoreCase = true) ==
            true
    ) {
      return RequestBodyFormat(RequestBodyFormat.Kind.MULTIPART, contentType, body.fields)
    }

    val byteFields = body.fields.filter { it.sample.value is ByteArray }
    if (byteFields.isEmpty()) {
      return RequestBodyFormat(RequestBodyFormat.Kind.JSON, contentType, body.fields)
    }

    require(byteFields.size == 1 && body.fields.size == 1) {
      "multipart가 아닌 요청 본문에는 ByteArray field 하나만 선언할 수 있습니다."
    }

    return RequestBodyFormat(RequestBodyFormat.Kind.RAW, contentType, body.fields)
  }
}
