package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import tools.jackson.databind.ObjectMapper

/** Documentation sample로 실제 MockMvc 요청을 생성합니다. */
internal class DocumentationRequestBuilder(
    private val objectMapper: ObjectMapper,
    private val requestBodyFormatResolver: RequestBodyFormatResolver = RequestBodyFormatResolver(),
) {
  fun build(documentation: Documentation): AbstractMockHttpServletRequestBuilder<*> {
    val requestLine = documentation.requestLine
    val pathValues = requestLine.pathVariables.map { requestValue(it.sample) }.toTypedArray()
    val bodyFormat =
        requestBodyFormatResolver.resolve(documentation.requestBody, documentation.requestHeaders)
    val request: AbstractMockHttpServletRequestBuilder<*> =
        if (bodyFormat.kind == RequestBodyFormat.Kind.MULTIPART) {
          multipart(requestLine.method, requestLine.uri, *pathValues)
        } else {
          request(requestLine.method, requestLine.uri, *pathValues)
        }

    requestLine.queryParameters.forEach { parameter ->
      request.queryParam(parameter.key, *requestValues(parameter.sample))
    }
    documentation.requestHeaders.headers.forEach { header ->
      if (!header.key.equals("Content-Type", ignoreCase = true)) {
        request.header(header.key, requestValue(header.sample))
      }
    }
    bodyFormat.effectiveContentType?.let { contentType ->
      request.contentType(contentType)
    }
    when (bodyFormat.kind) {
      RequestBodyFormat.Kind.NONE -> Unit
      RequestBodyFormat.Kind.JSON -> {
        request.content(objectMapper.writeValueAsBytes(requestBody(bodyFormat.fields)))
      }
      RequestBodyFormat.Kind.RAW -> {
        request.content(requireNotNull(bodyFormat.rawField).sample.value as ByteArray)
      }
      RequestBodyFormat.Kind.MULTIPART -> {
        val multipartRequest =
            request as? MockMultipartHttpServletRequestBuilder
                ?: error("multipart 요청 builder가 아닙니다.")
        bodyFormat.fields.forEach { field -> multipartRequest.file(multipartFile(field)) }
      }
    }

    return request
  }

  private fun requestValues(sample: Sample): Array<String> =
      when (val value = sample.value) {
        is Collection<*> -> queryValues(value)
        is Array<*> -> queryValues(value.asIterable())
        else -> arrayOf(requestValue(value))
      }

  private fun queryValues(values: Iterable<*>): Array<String> =
      values
          .map { value ->
            requestValue(requireNotNull(value) { "query parameter sample에는 null 원소를 사용할 수 없습니다." })
          }
          .toTypedArray()

  private fun requestValue(value: Any): String {
    val serialized = objectMapper.writeValueAsString(value)
    return if (serialized.startsWith('"') && serialized.endsWith('"')) {
      objectMapper.readValue(serialized, String::class.java)
    } else {
      serialized
    }
  }

  private fun requestValue(sample: Sample): String = requestValue(sample.value)

  private fun multipartFile(field: Field): MockMultipartFile {
    val value = field.sample.value
    val bytes: ByteArray
    val contentType: String
    val filename: String

    when (value) {
      is ByteArray -> {
        bytes = value
        contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        filename = field.key
      }
      is String -> {
        bytes = value.toByteArray(Charsets.UTF_8)
        contentType = "text/plain;charset=UTF-8"
        filename = ""
      }
      else -> {
        bytes = objectMapper.writeValueAsBytes(value)
        contentType = MediaType.APPLICATION_JSON_VALUE
        filename = ""
      }
    }

    return MockMultipartFile(field.key, filename, contentType, bytes)
  }

  private fun requestBody(fields: List<Field>): Map<String, Any> =
      linkedMapOf<String, Any>().apply {
        fields.forEach { field -> putField(field.key.split('.'), field.sample.value) }
      }

  private fun MutableMap<String, Any>.putField(path: List<String>, value: Any) {
    val segment = path.first()
    val key = segment.removeSuffix("[]")
    if (path.size == 1) {
      this[key] = if (segment.endsWith("[]") && value !is Collection<*>) listOf(value) else value
      return
    }

    if (segment.endsWith("[]")) {
      @Suppress("UNCHECKED_CAST")
      val items =
          getOrPut(key) { mutableListOf<MutableMap<String, Any>>() }
              as MutableList<MutableMap<String, Any>>
      val item = items.firstOrNull() ?: linkedMapOf<String, Any>().also(items::add)
      item.putField(path.drop(1), value)
      return
    }

    @Suppress("UNCHECKED_CAST")
    val child = getOrPut(key) { linkedMapOf<String, Any>() } as MutableMap<String, Any>
    child.putField(path.drop(1), value)
  }
}
