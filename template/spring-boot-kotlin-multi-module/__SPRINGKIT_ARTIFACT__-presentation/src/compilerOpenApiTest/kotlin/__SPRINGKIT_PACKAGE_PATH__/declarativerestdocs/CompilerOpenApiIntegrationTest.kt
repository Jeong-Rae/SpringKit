package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceDocumentation.headerWithName as resourceHeaderWithName
import com.epages.restdocs.apispec.ResourceDocumentation.parameterWithName as resourceParameterWithName
import com.epages.restdocs.apispec.ResourceDocumentation.resource
import com.epages.restdocs.apispec.ResourceSnippetParameters
import com.epages.restdocs.apispec.SimpleType
import com.fasterxml.jackson.annotation.JsonValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.snippet.Attributes
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class CompilerOpenApiIntegrationTest :
    FunSpec({
      val compilerSnippetsRoot = snippetsRoot("springkit.compiler-openapi.snippets")
      val manualSnippetsRoot = snippetsRoot("springkit.manual-baseline.snippets")
      val objectMapper = ObjectMapper()

      test("수동 기준선과 Compiler가 같은 resource 의미를 생성합니다") {
        val documentation = createUserDocumentation()

        documentOperation(
            snippetsRoot = compilerSnippetsRoot,
            testName = "compiler",
            snippets = compiler(objectMapper).compile(documentation).snippets,
        )
        documentOperation(
            snippetsRoot = manualSnippetsRoot,
            testName = "manual",
            snippets = manualSnippets(),
        )

        compilerSnippetsRoot.resourceFiles() shouldContainExactly
            listOf(compilerSnippetsRoot.resolve("create-user/resource.json"))

        val compilerResource = objectMapper.readTree(compilerSnippetsRoot.resourceFile().toFile())
        val manualResource = objectMapper.readTree(manualSnippetsRoot.resourceFile().toFile())

        compilerResource shouldBe manualResource
        assertResourceSemantics(compilerResource)
      }
    })

private fun assertResourceSemantics(resource: JsonNode) {
  resource.at("/operationId").stringValue() shouldBe "create-user"
  resource.at("/summary").stringValue() shouldBe "사용자 생성"
  resource.at("/description").stringValue() shouldBe "테넌트에 새로운 사용자를 생성합니다."
  resource.at("/tags/0").stringValue() shouldBe "users"
  resource.at("/request/path").stringValue() shouldBe "/tenants/{tenantId}/users"
  resource.at("/request/method").stringValue() shouldBe "POST"
  resource.at("/response/status").intValue() shouldBe 201

  assertValueElement(resource.elementAt("/request/pathParameters", "name", "tenantId"), "STRING")
  assertValueElement(resource.elementAt("/request/queryParameters", "name", "dryRun"), "BOOLEAN")
  assertValueElement(
      resource.elementAt("/request/queryParameters", "name", "page"),
      "INTEGER",
      optional = true,
  )
  assertValueElement(resource.elementAt("/request/headers", "name", "X-Request-Id"), "STRING")
  assertValueElement(resource.elementAt("/response/headers", "name", "Location"), "STRING")
  assertValueElement(resource.elementAt("/response/headers", "name", "X-RateLimit"), "INTEGER")

  listOf("/request/requestFields", "/response/responseFields").forEach { fields ->
    assertValueElement(resource.elementAt(fields, "path", "name"), "STRING")
    assertValueElement(resource.elementAt(fields, "path", "active"), "BOOLEAN")
    assertValueElement(resource.elementAt(fields, "path", "score"), "NUMBER")
    assertValueElement(resource.elementAt(fields, "path", "nickname"), "STRING", optional = true)

    val role = resource.elementAt(fields, "path", "role")
    assertValueElement(role, "enum")
    role.at("/attributes/enumValues").values().map { it.stringValue() } shouldBe
        listOf("user", "admin")

    val aliases = resource.elementAt(fields, "path", "aliases")
    assertValueElement(aliases, "ARRAY")
    aliases.at("/attributes/itemsType").stringValue() shouldBe "STRING"

    val roles = resource.elementAt(fields, "path", "roles")
    assertValueElement(roles, "ARRAY")
    roles.at("/attributes/itemsType").stringValue() shouldBe "ENUM"
    roles.at("/attributes/enumValues").values().map { it.stringValue() } shouldBe
        listOf("user", "admin")
  }
}

private fun JsonNode.elementAt(
    arrayPointer: String,
    key: String,
    value: String,
): JsonNode = at(arrayPointer).values().single { it[key].stringValue() == value }

private fun assertValueElement(
    element: JsonNode,
    type: String,
    optional: Boolean = false,
) {
  element["type"].stringValue() shouldBe type
  element["optional"].booleanValue() shouldBe optional
}

private fun snippetsRoot(property: String): Path =
    Path.of(requireNotNull(System.getProperty(property)) { "$property 시스템 프로퍼티가 필요합니다." })

private fun compiler(objectMapper: ObjectMapper): SpringRestDocsCompiler {
  val metadataResolver = ValueMetadataResolver(objectMapper)
  return SpringRestDocsCompiler(
      requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
      headerCompiler = HeaderCompiler(metadataResolver),
      bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
  )
}

private fun documentOperation(
    snippetsRoot: Path,
    testName: String,
    snippets: List<Snippet>,
) {
  val restDocumentation = ManualRestDocumentation(snippetsRoot.toString())
  restDocumentation.beforeTest(CompilerOpenApiIntegrationTest::class.java, testName)

  try {
    standaloneSetup(CreateUserController())
        .apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
        .build()
        .perform(createUserRequest())
        .andExpect(status().isCreated)
        .andDo(document("create-user", *snippets.toTypedArray()))
  } finally {
    restDocumentation.afterTest()
  }
}

private fun Path.resourceFiles(): List<Path> =
    Files.walk(this).use { paths ->
      paths.filter { it.fileName.toString() == "resource.json" }.sorted().toList()
    }

private fun Path.resourceFile(): Path = resolve("create-user/resource.json")

private fun createUserDocumentation(): Documentation =
    Documentation(
        name = "create-user",
        summary = "사용자 생성",
        description = "테넌트에 새로운 사용자를 생성합니다.",
        tags = setOf("users"),
        requestLine =
            RequestLine(
                method = HttpMethod.POST,
                uri = "/tenants/{tenantId}/users",
                pathVariables =
                    listOf(
                        PathVariable(
                            "tenantId",
                            "사용자를 생성할 테넌트 식별자",
                            sampleOf("tenant-1"),
                        )
                    ),
                queryParameters =
                    listOf(
                        QueryParameter("dryRun", "사용자 생성 검증만 수행할지 여부", sampleOf(false)),
                        QueryParameter("page", "결과 페이지", sampleOf(1), optional = true),
                    ),
            ),
        requestHeaders =
            Headers(listOf(Header("X-Request-Id", "요청 추적 식별자", sampleOf("request-123")))),
        requestBody = Body(coreRequestFields()),
        responseHeaders =
            Headers(
                listOf(
                    Header(HttpHeaders.LOCATION, "생성된 사용자 URI", sampleOf("/users/1")),
                    Header("X-RateLimit", "요청 제한", sampleOf(100)),
                )
            ),
        responseBody = Body(coreResponseFields()),
    )

private fun coreRequestFields(): List<Field> =
    listOf(
        Field("name", "사용자 이름", sampleOf("Alice")),
        Field("active", "활성 상태", sampleOf(true)),
        Field("score", "사용자 점수", sampleOf(1.5)),
        Field("role", "사용자 역할", sampleOf(ApiRole.ADMIN)),
        Field("aliases", "사용자 별칭", sampleOf(listOf("ally"))),
        Field("roles", "사용자 역할 목록", sampleOf(listOf(ApiRole.USER, ApiRole.ADMIN))),
        Field("nickname", "사용자 별명", sampleOf("ally"), optional = true),
    )

private fun coreResponseFields(): List<Field> =
    listOf(Field("id", "생성된 사용자 식별자", sampleOf("user-123"))) + coreRequestFields()

private fun manualSnippets(): List<Snippet> {
  val requestFieldDescriptors = manualRequestFieldDescriptors()
  val responseFieldDescriptors =
      listOf(fieldWithPath("id").type(JsonFieldType.STRING).description("생성된 사용자 식별자")) +
          manualRequestFieldDescriptors()

  return listOf(
      pathParameters(parameterWithName("tenantId").description("사용자를 생성할 테넌트 식별자")),
      queryParameters(
          parameterWithName("dryRun").description("사용자 생성 검증만 수행할지 여부"),
          parameterWithName("page").description("결과 페이지").optional(),
      ),
      requestHeaders(headerWithName("X-Request-Id").description("요청 추적 식별자")),
      requestFields(requestFieldDescriptors),
      responseHeaders(
          headerWithName(HttpHeaders.LOCATION).description("생성된 사용자 URI"),
          headerWithName("X-RateLimit").description("요청 제한"),
      ),
      responseFields(responseFieldDescriptors),
      resource(
          ResourceSnippetParameters.builder()
              .summary("사용자 생성")
              .description("테넌트에 새로운 사용자를 생성합니다.")
              .tag("users")
              .pathParameters(
                  resourceParameterWithName("tenantId")
                      .type(SimpleType.STRING)
                      .description("사용자를 생성할 테넌트 식별자")
              )
              .queryParameters(
                  resourceParameterWithName("dryRun")
                      .type(SimpleType.BOOLEAN)
                      .description("사용자 생성 검증만 수행할지 여부"),
                  resourceParameterWithName("page")
                      .type(SimpleType.INTEGER)
                      .description("결과 페이지")
                      .optional(),
              )
              .requestHeaders(
                  resourceHeaderWithName("X-Request-Id")
                      .type(SimpleType.STRING)
                      .description("요청 추적 식별자")
              )
              .requestFields(requestFieldDescriptors)
              .responseHeaders(
                  resourceHeaderWithName(HttpHeaders.LOCATION)
                      .type(SimpleType.STRING)
                      .description("생성된 사용자 URI"),
                  resourceHeaderWithName("X-RateLimit")
                      .type(SimpleType.INTEGER)
                      .description("요청 제한"),
              )
              .responseFields(responseFieldDescriptors)
              .build()
      ),
  )
}

private fun manualRequestFieldDescriptors() =
    listOf(
        fieldWithPath("name").type(JsonFieldType.STRING).description("사용자 이름"),
        fieldWithPath("active").type(JsonFieldType.BOOLEAN).description("활성 상태"),
        fieldWithPath("score").type(JsonFieldType.NUMBER).description("사용자 점수"),
        fieldWithPath("role")
            .type("enum")
            .description("사용자 역할")
            .attributes(Attributes.key("enumValues").value(listOf("user", "admin"))),
        fieldWithPath("aliases")
            .type(JsonFieldType.ARRAY)
            .description("사용자 별칭")
            .attributes(Attributes.key("itemsType").value("STRING")),
        fieldWithPath("roles")
            .type(JsonFieldType.ARRAY)
            .description("사용자 역할 목록")
            .attributes(
                Attributes.key("itemsType").value("ENUM"),
                Attributes.key("enumValues").value(listOf("user", "admin")),
            ),
        fieldWithPath("nickname").type(JsonFieldType.STRING).description("사용자 별명").optional(),
    )

private fun createUserRequest(): MockHttpServletRequestBuilder =
    post("/tenants/{tenantId}/users", "tenant-1")
        .queryParam("dryRun", "false")
        .header("X-Request-Id", "request-123")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """{"name":"Alice","active":true,"score":1.5,"role":"admin","aliases":["ally"],"roles":["user","admin"]}"""
        )

@RestController
private class CreateUserController {

  @PostMapping("/tenants/{tenantId}/users")
  fun createUser(
      @PathVariable tenantId: String,
      @RequestParam dryRun: Boolean,
      @RequestParam(required = false) page: Int?,
      @RequestHeader("X-Request-Id") requestId: String,
      @RequestBody request: String,
  ): ResponseEntity<String> {
    check(!dryRun)
    check(page == null)
    check(request.contains("Alice"))

    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.LOCATION, "/tenants/$tenantId/users/user-123")
        .header("X-Request-Id", requestId)
        .header("X-RateLimit", "100")
        .body(
            """{"id":"user-123","name":"Alice","active":true,"score":1.5,"role":"admin","aliases":["ally"],"roles":["user","admin"]}"""
        )
  }
}

private enum class ApiRole(@get:JsonValue val serializedValue: String) {
  USER("user"),
  ADMIN("admin"),
}
