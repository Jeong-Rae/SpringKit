package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.HttpMethod

data class Documentation(
    val name: String,
    val summary: String,
    val description: String,
    val tags: Set<String> = emptySet(),
    val requestLine: RequestLine,
    val requestHeaders: Headers = Headers(),
    val requestBody: Body = Body(),
    val responseHeaders: Headers = Headers(),
    val responseBody: Body = Body(),
)

data class RequestLine(
    val method: HttpMethod,
    val uri: String,
    val pathVariables: List<PathVariable> = emptyList(),
    val queryParameters: List<QueryParameter> = emptyList(),
)

sealed interface ValueElement {
    val key: String
    val description: String
    val sample: Sample
}

data class PathVariable(
    override val key: String,
    override val description: String,
    override val sample: Sample,
) : ValueElement

data class QueryParameter(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

data class Header(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

data class Field(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

data class Headers(
    val headers: List<Header> = emptyList(),
)

data class Body(
    val fields: List<Field> = emptyList(),
)
