package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.Documentation
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.FieldDescriptorCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.HeaderCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.HeaderDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.ParameterCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestLineCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestLineDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.SpringRestDocsCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.ValueMetadataResolver
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.documentation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.snippet.SnippetException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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

class CreateUserDocumentationTest :
    FunSpec({
      lateinit var mockMvc: MockMvc
      lateinit var restDocumentation: ManualRestDocumentation
      val metadataResolver = ValueMetadataResolver(ObjectMapper())
      val compiler =
          SpringRestDocsCompiler(
              requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
              headerCompiler = HeaderCompiler(metadataResolver),
              bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
          )

      fun executeDocumentation(
          documentation: Documentation,
          request: MockHttpServletRequestBuilder = createUserRequest(),
      ) {
        val compiled = compiler.compile(documentation)
        mockMvc
            .perform(request)
            .andDo(document(compiled.identifier, *compiled.snippets.toTypedArray()))
      }

      beforeTest { testCase ->
        restDocumentation = ManualRestDocumentation()
        restDocumentation.beforeTest(
            CreateUserDocumentationTest::class.java,
            testCase.name.name,
        )
        mockMvc =
            standaloneSetup(CreateUserController())
                .apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
                .build()
      }

      afterTest {
        restDocumentation.afterTest()
      }

      context("create-user 문서 생성") {
        test("요청과 응답을 문서화하면, 기준 문서 조각을 생성합니다") {
          val compiled = compiler.compile(createUserDocumentation())

          mockMvc
              .perform(createUserRequest())
              .andExpect(status().isCreated)
              .andExpect(header().string(HttpHeaders.LOCATION, "/tenants/tenant-1/users/user-123"))
              .andExpect(jsonPath("$.id").value("user-123"))
              .andDo(
                  document(
                      compiled.identifier,
                      *compiled.snippets.toTypedArray(),
                  )
              )

          Files.readString(
              Path.of("build/generated-snippets/create-user/request-headers.adoc")
          ) shouldNotContain "X-Trace-Id"
        }
      }

      context("create-user body field 검증") {
        test("required request field가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-required-field-mismatch",
                  requestBody = {
                    createUserRequestFields()
                    field("email", "이메일", sample = "alice@example.com")
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }

        test("optional request field가 없으면, 문서 생성에 성공합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-optional-field",
                  requestBody = {
                    createUserRequestFields()
                    field("nickname", "별명", sample = "ally", optional = true)
                  },
              )

          executeDocumentation(documentation)
        }

        test("ignored request field가 있으면, 검증에서 제외하고 문서에 출력하지 않습니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-ignored-field",
                  requestBody = {
                    createUserRequestFields()
                    ignoredField("legacyCode", "이전 코드", sample = "legacy")
                  },
              )

          executeDocumentation(
              documentation,
              createUserRequest("""{"name":"Alice","role":"ADMIN","legacyCode":"legacy"}"""),
          )

          Files.readString(
              Path.of("build/generated-snippets/create-user-ignored-field/request-fields.adoc")
          ) shouldNotContain "legacyCode"
        }

        test("request field 타입이 다르면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-field-type-mismatch",
                  requestBody = {
                    field("name", "사용자 이름", sample = "Alice")
                    field("role", "사용자 역할", sample = 1)
                  },
              )

          val exception = shouldThrow<RuntimeException> { executeDocumentation(documentation) }
          exception.javaClass.simpleName shouldBe "FieldTypesDoNotMatchException"
        }

        test("required response field가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-response-field-mismatch",
                  responseBody = {
                    createUserResponseFields()
                    field("email", "이메일", sample = "alice@example.com")
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }
      }

      context("create-user parameter와 header 검증") {
        test("required path parameter가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-path-parameter-mismatch",
                  requestLine = {
                    createUserRequestLine()
                    pathVariable("userId", "사용자 식별자", sample = "user-123")
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }

        test("required query parameter가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-query-parameter-mismatch",
                  requestLine = {
                    createUserRequestLine()
                    queryParameter("mode", "실행 방식", sample = "sync")
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }

        test("required request header가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-request-header-mismatch",
                  requestHeaders = {
                    createUserRequestHeaders()
                    header("X-Client-Id", "클라이언트 식별자", sample = "client-1")
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }

        test("required response header가 없으면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-response-header-mismatch",
                  responseHeaders = {
                    createUserResponseHeaders()
                    header("X-RateLimit", "요청 제한", sample = 100)
                  },
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }
      }

      context("create-user request line 검증") {
        test("HTTP method가 다르면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-method-mismatch",
                  method = "put",
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }

        test("URI template이 다르면, 문서 생성에 실패합니다") {
          val documentation =
              createUserDocumentation(
                  name = "create-user-uri-mismatch",
                  path = "/organizations/{tenantId}/users",
              )

          shouldThrow<SnippetException> {
            executeDocumentation(documentation)
          }
        }
      }
    })

private fun createUserDocumentation(
    name: String = "create-user",
    method: String = "post",
    path: String = "/tenants/{tenantId}/users",
    requestLine: RequestLineDsl.() -> Unit = { createUserRequestLine() },
    requestHeaders: HeaderDsl.() -> Unit = { createUserRequestHeaders() },
    requestBody: BodyDsl.() -> Unit = { createUserRequestFields() },
    responseHeaders: HeaderDsl.() -> Unit = { createUserResponseHeaders() },
    responseBody: BodyDsl.() -> Unit = { createUserResponseFields() },
): Documentation =
    documentation(name) {
      summary = "사용자 생성"
      description = "테넌트에 새로운 사용자를 생성합니다."
      tags("users")

      requestLine(method = method, path = path, block = requestLine)
      requestHeader(requestHeaders)
      requestBody(requestBody)
      responseHeader(responseHeaders)
      responseBody(responseBody)
    }

private fun RequestLineDsl.createUserRequestLine() {
  pathVariable("tenantId", "사용자를 생성할 테넌트 식별자", sample = "tenant-1")
  queryParameter("dryRun", "사용자 생성 검증만 수행할지 여부", sample = false)
}

private fun HeaderDsl.createUserRequestHeaders() {
  header("X-Request-Id", "요청 추적 식별자", sample = "request-123")
  ignoredHeader("X-Trace-Id", "요청 로깅 식별자", sample = "trace-999")
}

private fun BodyDsl.createUserRequestFields() {
  field("name", "사용자 이름", sample = "Alice")
  field<UserRole>("role", "사용자 역할", sample = UserRole.ADMIN)
}

private fun HeaderDsl.createUserResponseHeaders() {
  header(HttpHeaders.LOCATION, "생성된 사용자 URI", sample = "/users/1")
}

private fun BodyDsl.createUserResponseFields() {
  field("id", "생성된 사용자 식별자", sample = "user-123")
  field("name", "사용자 이름", sample = "Alice")
  field<UserRole>("role", "사용자 역할", sample = UserRole.ADMIN)
}

private enum class UserRole {
  ADMIN
}

private fun createUserRequest(
    content: String = """{"name":"Alice","role":"ADMIN"}""",
): MockHttpServletRequestBuilder =
    post("/tenants/{tenantId}/users", "tenant-1")
        .queryParam("dryRun", "false")
        .header("X-Request-Id", "request-123")
        .contentType(MediaType.APPLICATION_JSON)
        .content(content)

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
