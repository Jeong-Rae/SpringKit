package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

class CompilerOpenApiIntegrationTest :
    FunSpec({
      val snippetsRoot =
          Path.of(requireNotNull(System.getProperty("springkit.compiler-openapi.snippets")))
      lateinit var mockMvc: MockMvc
      lateinit var restDocumentation: ManualRestDocumentation

      beforeTest {
        restDocumentation = ManualRestDocumentation(snippetsRoot.toString())
        restDocumentation.beforeTest(CompilerOpenApiIntegrationTest::class.java, "compiler-openapi")
        mockMvc =
            standaloneSetup(CreateUserController())
                .apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
                .build()
      }

      afterTest {
        restDocumentation.afterTest()
      }

      test("Compiler integration 결과만 OpenAPI 집계 입력으로 생성합니다") {
        val metadataResolver = ValueMetadataResolver(ObjectMapper())
        val compiler =
            SpringRestDocsCompiler(
                requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
                headerCompiler = HeaderCompiler(metadataResolver),
                bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
            )
        val compiled = compiler.compile(createUserDocumentation())

        mockMvc
            .perform(
                post("/tenants/{tenantId}/users", "tenant-1")
                    .queryParam("dryRun", "false")
                    .header("X-Request-Id", "request-123")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Alice","role":"ADMIN"}""")
            )
            .andExpect(status().isCreated)
            .andDo(document(compiled.identifier, *compiled.snippets.toTypedArray()))

        Files.walk(snippetsRoot).use { paths ->
          paths
              .filter { it.fileName.toString() == "resource.json" }
              .map { it.relativeTo(snippetsRoot).toString() }
              .toList() shouldContainExactly listOf("create-user/resource.json")
        }
      }
    })

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
                    listOf(QueryParameter("dryRun", "사용자 생성 검증만 수행할지 여부", sampleOf(false))),
            ),
        requestHeaders =
            Headers(listOf(Header("X-Request-Id", "요청 추적 식별자", sampleOf("request-123")))),
        requestBody =
            Body(
                listOf(
                    Field("name", "사용자 이름", sampleOf("Alice")),
                    Field("role", "사용자 역할", sampleOf("ADMIN")),
                )
            ),
        responseHeaders =
            Headers(listOf(Header(HttpHeaders.LOCATION, "생성된 사용자 URI", sampleOf("/users/1")))),
        responseBody =
            Body(
                listOf(
                    Field("id", "생성된 사용자 식별자", sampleOf("user-123")),
                    Field("name", "사용자 이름", sampleOf("Alice")),
                    Field("role", "사용자 역할", sampleOf("ADMIN")),
                )
            ),
    )

@RestController
private class CreateUserController {

  @PostMapping("/tenants/{tenantId}/users")
  fun createUser(
      @PathVariable tenantId: String,
      @RequestParam dryRun: Boolean,
      @RequestHeader("X-Request-Id") requestId: String,
      @RequestBody request: String,
  ): ResponseEntity<String> {
    check(!dryRun)
    check(request.contains("Alice"))

    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.LOCATION, "/tenants/$tenantId/users/user-123")
        .header("X-Request-Id", requestId)
        .body("""{"id":"user-123","name":"Alice","role":"ADMIN"}""")
  }
}
