package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceSnippet
import com.epages.restdocs.apispec.SimpleType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import tools.jackson.databind.ObjectMapper

class ResourceSnippetComposerTest :
    FunSpec({
      val metadataResolver = ValueMetadataResolver(ObjectMapper())
      val requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver))
      val headerCompiler = HeaderCompiler(metadataResolver)
      val bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver))
      val composer = ResourceSnippetComposer()

      test("컴파일 결과를 조합하면, resource metadata와 typed descriptor를 보존합니다") {
        val documentation = resourceDocumentation()
        val requestLine = requestLineCompiler.compile(documentation.requestLine)
        val requestHeaders = headerCompiler.compile(documentation.requestHeaders)
        val requestBody = bodyCompiler.compile(documentation.requestBody)
        val responseHeaders = headerCompiler.compile(documentation.responseHeaders)
        val responseBody = bodyCompiler.compile(documentation.responseBody)

        val parameters =
            composer.composeParameters(
                documentation,
                requestLine,
                requestHeaders,
                requestBody,
                responseHeaders,
                responseBody,
            )

        parameters.summary shouldBe "사용자 생성"
        parameters.description shouldBe "새로운 사용자를 생성한다."
        parameters.tags shouldBe setOf("users", "write")
        parameters.pathParameters.map { it.name to it.type } shouldContainExactly
            listOf("tenantId" to SimpleType.STRING)
        parameters.queryParameters.map { it.name to it.type } shouldContainExactly
            listOf("dryRun" to SimpleType.BOOLEAN)
        parameters.requestHeaders.map { it.name to it.type } shouldContainExactly
            listOf("X-Request-Id" to SimpleType.STRING)
        parameters.responseHeaders.map { it.name to it.type } shouldContainExactly
            listOf("Location" to SimpleType.STRING)
      }

      test("resource parameter는 standard snippet과 동일한 body descriptor를 사용합니다") {
        val documentation = resourceDocumentation()
        val requestLine = requestLineCompiler.compile(documentation.requestLine)
        val requestHeaders = headerCompiler.compile(documentation.requestHeaders)
        val requestBody = bodyCompiler.compile(documentation.requestBody)
        val responseHeaders = headerCompiler.compile(documentation.responseHeaders)
        val responseBody = bodyCompiler.compile(documentation.responseBody)

        val parameters =
            composer.composeParameters(
                documentation,
                requestLine,
                requestHeaders,
                requestBody,
                responseHeaders,
                responseBody,
            )

        (parameters.requestFields.single() === requestBody.fields.single()) shouldBe true
        (parameters.responseFields.single() === responseBody.fields.single()) shouldBe true
        composer.compose(
            documentation,
            requestLine,
            requestHeaders,
            requestBody,
            responseHeaders,
            responseBody,
        )::class shouldBe ResourceSnippet::class
      }
    })

private fun resourceDocumentation(): Documentation =
    Documentation(
        name = "create-user",
        summary = "사용자 생성",
        description = "새로운 사용자를 생성한다.",
        tags = linkedSetOf("users", "write"),
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
