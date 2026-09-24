package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import java.util.Locale
import org.springframework.http.HttpMethod

/** Documentation builder의 수신 객체 범위를 제한합니다. */
@DslMarker annotation class DocumentationDslMarker

/** 선언형 API 문서를 내부 Core [Documentation]으로 생성합니다. */
internal fun documentationDefinition(
    name: String,
    block: DocumentationDsl.() -> Unit,
): Documentation {
  require(name.isNotBlank()) { "문서 이름은 비어 있을 수 없습니다." }
  return DocumentationDsl(name).apply(block).build()
}

/** API 문서의 최상위 DSL입니다. */
@DocumentationDslMarker
class DocumentationDsl internal constructor(private val name: String) {
  private var summaryValue: String? = null
  private var descriptionValue: String? = null
  private val tags = linkedSetOf<String>()
  private var requestLine: RequestLine? = null
  private var requestHeaders: Headers? = null
  private var requestBody: Body? = null
  private var responseHeaders: Headers? = null
  private var responseBody: Body? = null

  var summary: String
    get() = summaryValue ?: error("summary가 선언되지 않았습니다.")
    set(value) {
      summaryValue = value
    }

  var description: String
    get() = descriptionValue ?: error("description이 선언되지 않았습니다.")
    set(value) {
      descriptionValue = value
    }

  fun tags(vararg tags: String) {
    this.tags.addAll(tags)
  }

  fun requestLine(
      method: String,
      path: String,
      block: RequestLineDsl.() -> Unit = {},
  ) {
    check(requestLine == null) { "requestLine은 한 번만 선언할 수 있습니다." }
    requestLine = RequestLineDsl(method, path).apply(block).build()
  }

  fun requestHeader(block: HeaderDsl.() -> Unit) {
    check(requestHeaders == null) { "requestHeader는 한 번만 선언할 수 있습니다." }
    requestHeaders = HeaderDsl().apply(block).build()
  }

  fun requestBody(block: BodyDsl.() -> Unit) {
    check(requestBody == null) { "requestBody는 한 번만 선언할 수 있습니다." }
    requestBody = BodyDsl().apply(block).build()
  }

  fun responseHeader(block: HeaderDsl.() -> Unit) {
    check(responseHeaders == null) { "responseHeader는 한 번만 선언할 수 있습니다." }
    responseHeaders = HeaderDsl().apply(block).build()
  }

  fun responseBody(block: BodyDsl.() -> Unit) {
    check(responseBody == null) { "responseBody는 한 번만 선언할 수 있습니다." }
    responseBody = BodyDsl().apply(block).build()
  }

  internal fun build(): Documentation =
      Documentation(
          name = name,
          summary = summaryValue ?: error("summary가 선언되지 않았습니다."),
          description = descriptionValue ?: error("description이 선언되지 않았습니다."),
          tags = tags.toSet(),
          requestLine = requestLine ?: error("requestLine이 선언되지 않았습니다."),
          requestHeaders = requestHeaders ?: Headers(),
          requestBody = requestBody ?: Body(),
          responseHeaders = responseHeaders ?: Headers(),
          responseBody = responseBody ?: Body(),
      )
}

/** HTTP 요청 행과 parameter를 선언합니다. */
@DocumentationDslMarker
class RequestLineDsl internal constructor(method: String, path: String) {
  private val method = parseHttpMethod(method)
  private val path = path
  private val pathVariables = mutableListOf<PathVariable>()
  private val queryParameters = mutableListOf<QueryParameter>()

  inline fun <reified T : Any> pathVariable(
      key: String,
      description: String,
      sample: T,
  ) {
    addPathVariable(PathVariable(key, description, sampleOf(sample)))
  }

  inline fun <reified T : Any> queryParameter(
      key: String,
      description: String,
      sample: T,
      optional: Boolean = false,
  ) {
    addQueryParameter(
        QueryParameter(
            key = key,
            description = description,
            sample = sampleOf(sample),
            optional = optional,
        )
    )
  }

  inline fun <reified T : Any> ignoredQueryParameter(
      key: String,
      description: String,
      sample: T,
  ) {
    addQueryParameter(
        QueryParameter(
            key = key,
            description = description,
            sample = sampleOf(sample),
            ignored = true,
        )
    )
  }

  @PublishedApi
  internal fun addPathVariable(pathVariable: PathVariable) {
    pathVariables += pathVariable
  }

  @PublishedApi
  internal fun addQueryParameter(queryParameter: QueryParameter) {
    queryParameters += queryParameter
  }

  internal fun build(): RequestLine =
      RequestLine(
          method = method,
          uri = path,
          pathVariables = pathVariables.toList(),
          queryParameters = queryParameters.toList(),
      )
}

/** 요청 또는 응답 header를 선언합니다. */
@DocumentationDslMarker
class HeaderDsl internal constructor() {
  private val headers = mutableListOf<Header>()

  inline fun <reified T : Any> header(
      key: String,
      description: String,
      sample: T,
      optional: Boolean = false,
  ) {
    addHeader(
        Header(
            key = key,
            description = description,
            sample = sampleOf(sample),
            optional = optional,
        )
    )
  }

  inline fun <reified T : Any> ignoredHeader(
      key: String,
      description: String,
      sample: T,
  ) {
    addHeader(
        Header(
            key = key,
            description = description,
            sample = sampleOf(sample),
            ignored = true,
        )
    )
  }

  @PublishedApi
  internal fun addHeader(header: Header) {
    headers += header
  }

  internal fun build(): Headers = Headers(headers.toList())
}

/** 요청 또는 응답 body field를 선언합니다. */
@DocumentationDslMarker
class BodyDsl internal constructor() {
  private val fields = mutableListOf<Field>()

  inline fun <reified T : Any> field(
      key: String,
      description: String,
      sample: T,
      optional: Boolean = false,
  ) {
    addField(
        Field(
            key = key,
            description = description,
            sample = sampleOf(sample),
            optional = optional,
        )
    )
  }

  inline fun <reified T : Any> ignoredField(
      key: String,
      description: String,
      sample: T,
  ) {
    addField(
        Field(
            key = key,
            description = description,
            sample = sampleOf(sample),
            ignored = true,
        )
    )
  }

  @PublishedApi
  internal fun addField(field: Field) {
    fields += field
  }

  internal fun build(): Body = Body(fields.toList())
}

private fun parseHttpMethod(method: String): HttpMethod =
    HttpMethod.valueOf(method.uppercase(Locale.ROOT))
