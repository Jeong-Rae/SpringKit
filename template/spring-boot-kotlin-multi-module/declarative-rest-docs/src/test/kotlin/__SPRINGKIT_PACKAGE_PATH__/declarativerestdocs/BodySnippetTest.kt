package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.fasterxml.jackson.annotation.JsonValue
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.StringWriter
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContext
import org.springframework.restdocs.operation.OperationRequestFactory
import org.springframework.restdocs.operation.OperationResponseFactory
import org.springframework.restdocs.operation.StandardOperation
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.snippet.SnippetException
import org.springframework.restdocs.snippet.WriterResolver
import org.springframework.restdocs.templates.Template
import org.springframework.restdocs.templates.TemplateEngine
import tools.jackson.databind.ObjectMapper

class BodySnippetTest :
    FunSpec({
      val compiler =
          BodyCompiler(
              FieldDescriptorCompiler(
                  ValueMetadataResolver(ObjectMapper()),
              )
          )

      context("request와 response body snippet") {
        test("컴파일한 fields와 실제 request 및 response body가 일치하면, 검증에 성공한다") {
          val requestBody =
              Body(
                  listOf(
                      Field("name", "사용자 이름", sampleOf("Alice")),
                      Field(
                          key = "profile.nickname",
                          description = "사용자 별명",
                          sample = sampleOf("ally"),
                          optional = true,
                      ),
                      Field(
                          key = "roles",
                          description = "사용자 역할 목록",
                          sample = sampleOf(listOf(SnippetRole.USER, SnippetRole.ADMIN)),
                      ),
                      Field("role", "사용자 역할", sampleOf(SnippetRole.ADMIN)),
                      Field(
                          key = "legacyCode",
                          description = "이전 시스템 코드",
                          sample = sampleOf("legacy"),
                          ignored = true,
                      ),
                  )
              )
          val responseBody =
              Body(
                  listOf(
                      Field("id", "생성된 사용자 식별자", sampleOf("user-123")),
                      Field("name", "사용자 이름", sampleOf("Alice")),
                      Field("role", "사용자 역할", sampleOf(SnippetRole.ADMIN)),
                  )
              )
          val operation =
              operation(
                  requestContent =
                      """{"name":"Alice","roles":["user","admin"],"role":"admin","legacyCode":"legacy"}""",
                  responseContent = """{"id":"user-123","name":"Alice","role":"admin"}""",
              )

          shouldNotThrowAny {
            requestFields(compiler.compile(requestBody).fields).document(operation)
            responseFields(compiler.compile(responseBody).fields).document(operation)
          }
        }

        test("request body에서 required Field가 누락되면, 검증에 실패한다") {
          val compiled = compiler.compile(Body(listOf(Field("name", "사용자 이름", sampleOf("Alice")))))
          val operation = operation(requestContent = "{}", responseContent = "{}")

          shouldThrow<SnippetException> {
            requestFields(compiled.fields).document(operation)
          }
        }

        test("response body의 primitive 타입이 다르면, 검증에 실패한다") {
          val compiled =
              compiler.compile(Body(listOf(Field("id", "생성된 사용자 식별자", sampleOf("user-123")))))
          val operation = operation(requestContent = "{}", responseContent = """{"id":123}""")

          val exception =
              shouldThrow<RuntimeException> {
                responseFields(compiled.fields).document(operation)
              }

          exception.javaClass.simpleName shouldBe "FieldTypesDoNotMatchException"
        }
      }
    })

private fun operation(
    requestContent: String,
    responseContent: String,
): StandardOperation {
  val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
  val request =
      OperationRequestFactory()
          .create(
              URI.create("https://example.com/users"),
              HttpMethod.POST,
              requestContent.toByteArray(),
              headers,
              emptyList(),
          )
  val response =
      OperationResponseFactory()
          .create(
              HttpStatus.OK,
              headers,
              responseContent.toByteArray(),
          )

  return StandardOperation(
      "body-snippet",
      request,
      response,
      snippetAttributes(),
  )
}

private fun snippetAttributes(): Map<String, Any> =
    mapOf(
        RestDocumentationContext::class.java.name to TestRestDocumentationContext,
        WriterResolver::class.java.name to
            WriterResolver { _, _, _ ->
              StringWriter()
            },
        TemplateEngine::class.java.name to
            TemplateEngine {
              Template { "" }
            },
    )

private object TestRestDocumentationContext : RestDocumentationContext {
  override fun getTestClass(): Class<*> = BodySnippetTest::class.java

  override fun getTestMethodName(): String = "bodySnippet"

  override fun getStepCount(): Int = 0

  override fun getOutputDirectory(): File = File("build/generated-snippets")
}

private enum class SnippetRole(@get:JsonValue val serializedValue: String) {
  USER("user"),
  ADMIN("admin"),
}
