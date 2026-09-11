package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import tools.jackson.databind.ObjectMapper

class SpringRestDocsCompilerTest :
    FunSpec({
      val metadataResolver = ValueMetadataResolver(ObjectMapper())
      val compiler =
          SpringRestDocsCompiler(
              requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
              headerCompiler = HeaderCompiler(metadataResolver),
              bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
          )

      test("Documentation을 컴파일하면, identifier와 전체 snippet을 명세 순서로 생성합니다") {
        val compiled = compiler.compile(fullDocumentation())

        compiled.identifier shouldBe "create-user"
        compiled.snippets.map { it.javaClass.simpleName } shouldContainExactly
            listOf(
                "RequestLineValidationSnippet",
                "PathParametersSnippet",
                "QueryParametersSnippet",
                "RequestHeadersSnippet",
                "RequestFieldsSnippet",
                "ResponseHeadersSnippet",
                "ResponseFieldsSnippet",
                "ResourceSnippet",
            )
      }

      test("선택 context가 비어 있으면, validation과 resource snippet만 생성합니다") {
        val compiled =
            compiler.compile(
                Documentation(
                    name = "list-users",
                    summary = "사용자 목록",
                    description = "사용자 목록을 조회한다.",
                    requestLine = RequestLine(HttpMethod.GET, "/users"),
                )
            )

        compiled.identifier shouldBe "list-users"
        compiled.snippets.map { it.javaClass.simpleName } shouldContainExactly
            listOf("RequestLineValidationSnippet", "ResourceSnippet")
      }
    })

private fun fullDocumentation(): Documentation =
    Documentation(
        name = "create-user",
        summary = "사용자 생성",
        description = "새로운 사용자를 생성한다.",
        tags = setOf("users"),
        requestLine =
            RequestLine(
                method = HttpMethod.POST,
                uri = "/tenants/{tenantId}/users",
                pathVariables = listOf(PathVariable("tenantId", "테넌트 ID", sampleOf("tenant-1"))),
                queryParameters = listOf(QueryParameter("dryRun", "검증 여부", sampleOf(false))),
            ),
        requestHeaders = Headers(listOf(Header("X-Request-Id", "요청 ID", sampleOf("request-1")))),
        requestBody = Body(listOf(Field("name", "사용자 이름", sampleOf("Alice")))),
        responseHeaders = Headers(listOf(Header("Location", "생성 URI", sampleOf("/users/1")))),
        responseBody = Body(listOf(Field("id", "사용자 ID", sampleOf("user-1")))),
    )
