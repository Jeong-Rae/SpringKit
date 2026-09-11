package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.http.HttpMethod
import tools.jackson.databind.ObjectMapper

class StandardSnippetComposerTest :
    FunSpec({
      val metadataResolver = ValueMetadataResolver(ObjectMapper())
      val requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver))
      val headerCompiler = HeaderCompiler(metadataResolver)
      val bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver))
      val composer = StandardSnippetComposer()

      test("모든 HTTP context에 요소가 있으면, 표준 snippet을 명세 순서로 생성합니다") {
        val snippets =
            composer.compose(
                requestLine =
                    requestLineCompiler.compile(
                        RequestLine(
                            method = HttpMethod.POST,
                            uri = "/users/{userId}",
                            pathVariables = listOf(PathVariable("userId", "사용자 ID", sampleOf(1L))),
                            queryParameters =
                                listOf(QueryParameter("detail", "상세 조회 여부", sampleOf(true))),
                        )
                    ),
                requestHeaders =
                    headerCompiler.compile(
                        Headers(listOf(Header("X-Request-Id", "요청 ID", sampleOf("request-1"))))
                    ),
                requestBody =
                    bodyCompiler.compile(Body(listOf(Field("name", "사용자 이름", sampleOf("Alice"))))),
                responseHeaders =
                    headerCompiler.compile(
                        Headers(listOf(Header("Location", "생성 URI", sampleOf("/users/1"))))
                    ),
                responseBody =
                    bodyCompiler.compile(Body(listOf(Field("id", "사용자 ID", sampleOf(1L))))),
            )

        snippets.map { it.javaClass.simpleName } shouldContainExactly
            listOf(
                "RequestLineValidationSnippet",
                "PathParametersSnippet",
                "QueryParametersSnippet",
                "RequestHeadersSnippet",
                "RequestFieldsSnippet",
                "ResponseHeadersSnippet",
                "ResponseFieldsSnippet",
            )
      }

      test("선택 HTTP context가 비어 있으면, validation snippet만 생성합니다") {
        val snippets =
            composer.compose(
                requestLine =
                    requestLineCompiler.compile(
                        RequestLine(
                            method = HttpMethod.GET,
                            uri = "/users",
                        )
                    ),
                requestHeaders = headerCompiler.compile(Headers()),
                requestBody = bodyCompiler.compile(Body()),
                responseHeaders = headerCompiler.compile(Headers()),
                responseBody = bodyCompiler.compile(Body()),
            )

        snippets.map { it.javaClass.simpleName } shouldContainExactly
            listOf("RequestLineValidationSnippet")
      }

      test("Header context에 ignored 요소만 있으면, 해당 header snippet을 생성하지 않습니다") {
        val snippets =
            composer.compose(
                requestLine =
                    requestLineCompiler.compile(
                        RequestLine(
                            method = HttpMethod.GET,
                            uri = "/users",
                        )
                    ),
                requestHeaders =
                    headerCompiler.compile(
                        Headers(
                            listOf(
                                Header(
                                    key = "X-Debug",
                                    description = "디버깅 정보",
                                    sample = sampleOf(true),
                                    ignored = true,
                                )
                            )
                        )
                    ),
                requestBody = bodyCompiler.compile(Body()),
                responseHeaders = headerCompiler.compile(Headers()),
                responseBody = bodyCompiler.compile(Body()),
            )

        snippets.map { it.javaClass.simpleName } shouldContainExactly
            listOf("RequestLineValidationSnippet")
      }
    })
