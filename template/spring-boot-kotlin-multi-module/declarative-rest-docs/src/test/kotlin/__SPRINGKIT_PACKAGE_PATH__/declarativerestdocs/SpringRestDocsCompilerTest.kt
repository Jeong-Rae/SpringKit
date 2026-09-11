package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceSnippet
import com.epages.restdocs.apispec.ResourceSnippetParameters
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

      test("같은 Documentation을 반복 컴파일하면, 실행 순서와 무관하게 같은 구조와 resource 의미를 생성합니다") {
        val documentation = fullDocumentation()
        val first = compiler.compile(documentation)

        compiler.compile(
            Documentation(
                name = "health",
                summary = "상태 확인",
                description = "서비스 상태를 확인한다.",
                requestLine = RequestLine(HttpMethod.GET, "/health"),
            )
        )

        val second = compiler.compile(documentation)

        first.identifier shouldBe second.identifier
        first.snippets.map { it.javaClass.name } shouldBe second.snippets.map { it.javaClass.name }
        first.resourceSemantics() shouldBe second.resourceSemantics()
      }
    })

private fun CompiledDocumentation.resourceSemantics(): List<Any?> {
  val resourceSnippet = snippets.filterIsInstance<ResourceSnippet>().single()
  val parametersField = ResourceSnippet::class.java.getDeclaredField("resourceSnippetParameters")
  parametersField.isAccessible = true
  val parameters = parametersField.get(resourceSnippet) as ResourceSnippetParameters

  return listOf(
      parameters.summary,
      parameters.description,
      parameters.tags,
      parameters.pathParameters.map { listOf(it.name, it.description, it.type, it.optional) },
      parameters.queryParameters.map { listOf(it.name, it.description, it.type, it.optional) },
      parameters.requestHeaders.map { listOf(it.name, it.description, it.type, it.optional) },
      parameters.requestFields.map {
        listOf(it.path, it.description, it.type, it.isOptional, it.isIgnored, it.attributes)
      },
      parameters.responseHeaders.map { listOf(it.name, it.description, it.type, it.optional) },
      parameters.responseFields.map {
        listOf(it.path, it.description, it.type, it.isOptional, it.isIgnored, it.attributes)
      },
  )
}

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
