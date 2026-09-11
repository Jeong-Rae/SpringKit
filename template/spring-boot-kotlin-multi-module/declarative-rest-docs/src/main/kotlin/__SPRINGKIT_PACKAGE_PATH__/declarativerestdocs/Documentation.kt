package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.HttpMethod

/** 컴파일러가 해석할 API 문서의 최상위 입력입니다. */
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

/** 문서가 기대하는 HTTP 메서드, URI와 요청 파라미터를 표현합니다. */
data class RequestLine(
    val method: HttpMethod,
    val uri: String,
    val pathVariables: List<PathVariable> = emptyList(),
    val queryParameters: List<QueryParameter> = emptyList(),
)

/** HTTP 값 요소가 공통으로 제공하는 문서 정보입니다. */
sealed interface ValueElement {
    val key: String
    val description: String
    val sample: Sample
}

/** URI 경로에서 항상 필요한 값을 표현합니다. */
data class PathVariable(
    override val key: String,
    override val description: String,
    override val sample: Sample,
) : ValueElement

/** 질의 파라미터의 선택 및 문서 제외 상태를 표현합니다. */
data class QueryParameter(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

/** 요청 또는 응답 헤더의 선택 및 문서 제외 상태를 표현합니다. */
data class Header(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

/** 요청 또는 응답 본문 필드의 선택 및 문서 제외 상태를 표현합니다. */
data class Field(
    override val key: String,
    override val description: String,
    override val sample: Sample,
    val optional: Boolean = false,
    val ignored: Boolean = false,
) : ValueElement

/** 요청 또는 응답 헤더를 선언된 순서로 보존합니다. */
data class Headers(val headers: List<Header> = emptyList())

/** 요청 또는 응답 본문 필드를 선언된 순서로 보존합니다. */
data class Body(val fields: List<Field> = emptyList())
