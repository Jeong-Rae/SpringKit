package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import tools.jackson.databind.ObjectMapper

/** Documentation sample로 실제 MockMvc 요청을 생성합니다. */
internal class DocumentationRequestBuilder(private val objectMapper: ObjectMapper) {
  fun build(documentation: Documentation): MockHttpServletRequestBuilder {
    val requestLine = documentation.requestLine
    val pathValues = requestLine.pathVariables.map { requestValue(it.sample) }.toTypedArray()
    val request = request(requestLine.method, requestLine.uri, *pathValues)

    requestLine.queryParameters.forEach { parameter ->
      request.queryParam(parameter.key, *requestValues(parameter.sample))
    }
    documentation.requestHeaders.headers.forEach { header ->
      request.header(header.key, requestValue(header.sample))
    }
    documentation.requestBody.fields.takeIf(List<Field>::isNotEmpty)?.let { fields ->
      if (documentation.requestHeaders.headers.none { it.key.equals("Content-Type", ignoreCase = true) }) {
        request.contentType(MediaType.APPLICATION_JSON)
      }
      request.content(objectMapper.writeValueAsString(requestBody(fields)))
    }

    return request
  }

  private fun requestValues(sample: Sample): Array<String> =
      when (val value = sample.value) {
        is Collection<*> -> value.map(::requestValue).toTypedArray()
        is Array<*> -> value.map(::requestValue).toTypedArray()
        else -> arrayOf(requestValue(value))
      }

  private fun requestValue(value: Any?): String {
    val serialized = objectMapper.writeValueAsString(value)
    return if (serialized.startsWith('"') && serialized.endsWith('"')) {
      objectMapper.readValue(serialized, String::class.java)
    } else {
      serialized
    }
  }

  private fun requestValue(sample: Sample): String = requestValue(sample.value)

  private fun requestBody(fields: List<Field>): Map<String, Any?> =
      linkedMapOf<String, Any?>().apply {
        fields.forEach { field -> putField(field.key.split('.'), field.sample.value) }
      }

  private fun MutableMap<String, Any?>.putField(path: List<String>, value: Any?) {
    val segment = path.first()
    val key = segment.removeSuffix("[]")
    if (path.size == 1) {
      this[key] = if (segment.endsWith("[]") && value !is Collection<*>) listOf(value) else value
      return
    }

    if (segment.endsWith("[]")) {
      @Suppress("UNCHECKED_CAST")
      val items =
          getOrPut(key) { mutableListOf<MutableMap<String, Any?>>() }
              as MutableList<MutableMap<String, Any?>>
      val item = items.firstOrNull() ?: linkedMapOf<String, Any?>().also(items::add)
      item.putField(path.drop(1), value)
      return
    }

    @Suppress("UNCHECKED_CAST")
    val child = getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
    child.putField(path.drop(1), value)
  }
}
