package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.SimpleType
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.StringWriter
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.restdocs.RestDocumentationContext
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.operation.OperationRequestFactory
import org.springframework.restdocs.operation.OperationResponseFactory
import org.springframework.restdocs.operation.StandardOperation
import org.springframework.restdocs.snippet.WriterResolver
import org.springframework.restdocs.templates.Template
import org.springframework.restdocs.templates.TemplateEngine
import tools.jackson.databind.ObjectMapper

class HeaderCompilerTest :
    FunSpec({
      val compiler = HeaderCompiler(ValueMetadataResolver(ObjectMapper()))

      context("Header 컴파일") {
        test("Header를 컴파일하면, 양쪽 descriptor에 정보와 typed metadata가 적용됩니다") {
          val compiled =
              requireNotNull(compiler.compile(Header("X-Retry-Count", "재시도 횟수", sampleOf(3))))

          compiled.descriptor.name shouldBe "X-Retry-Count"
          compiled.descriptor.description shouldBe "재시도 횟수"
          compiled.descriptor.isOptional shouldBe false
          compiled.resourceDescriptor.name shouldBe "X-Retry-Count"
          compiled.resourceDescriptor.description shouldBe "재시도 횟수"
          compiled.resourceDescriptor.type shouldBe SimpleType.INTEGER
          compiled.resourceDescriptor.optional shouldBe false
        }

        test("optional Header를 컴파일하면, 양쪽 descriptor에 optional 상태가 적용됩니다") {
          val compiled =
              requireNotNull(
                  compiler.compile(
                      Header(
                          key = "X-Request-Id",
                          description = "요청 추적 식별자",
                          sample = sampleOf("request-1"),
                          optional = true,
                      )
                  )
              )

          compiled.descriptor.isOptional shouldBe true
          compiled.resourceDescriptor.type shouldBe SimpleType.STRING
          compiled.resourceDescriptor.optional shouldBe true
        }

        test("ignored Header를 컴파일하면, 문서 descriptor를 생성하지 않습니다") {
          compiler
              .compile(
                  Header(
                      key = "X-Debug",
                      description = "디버깅 정보",
                      sample = sampleOf(true),
                      ignored = true,
                  )
              )
              .shouldBeNull()
        }
      }

      test("같은 컴파일 규칙으로 생성한 descriptor는 request와 response header snippet에서 동작합니다") {
        val requestHeader =
            requireNotNull(
                compiler.compile(Header("X-Request-Id", "요청 추적 식별자", sampleOf("request-1")))
            )
        val responseHeader =
            requireNotNull(
                compiler.compile(Header(HttpHeaders.LOCATION, "생성된 사용자 URI", sampleOf("/users/1")))
            )
        val operation = headerOperation()

        shouldNotThrowAny {
          requestHeaders(requestHeader.descriptor).document(operation)
          responseHeaders(responseHeader.descriptor).document(operation)
        }
      }

      test("Header 하나를 컴파일하면, enum sample metadata가 한 번만 해석됩니다") {
        CountingHeaderRole.serializationCount = 0

        compiler.compile(Header("X-Role", "사용자 역할", sampleOf(CountingHeaderRole.ADMIN)))

        CountingHeaderRole.serializationCount shouldBe CountingHeaderRole.entries.size
      }
    })

private fun headerOperation(): StandardOperation {
  val requestHeaders = HttpHeaders().apply { set("X-Request-Id", "request-1") }
  val responseHeaders = HttpHeaders().apply { location = URI.create("/users/1") }
  val request =
      OperationRequestFactory()
          .create(
              URI.create("https://example.com/users"),
              HttpMethod.POST,
              ByteArray(0),
              requestHeaders,
              emptyList(),
          )
  val response =
      OperationResponseFactory().create(HttpStatus.CREATED, responseHeaders, ByteArray(0))

  return StandardOperation(
      "header-snippet",
      request,
      response,
      headerSnippetAttributes(),
  )
}

private fun headerSnippetAttributes(): Map<String, Any> =
    mapOf(
        RestDocumentationContext::class.java.name to HeaderTestRestDocumentationContext,
        WriterResolver::class.java.name to
            WriterResolver { _, _, _ ->
              StringWriter()
            },
        TemplateEngine::class.java.name to
            TemplateEngine {
              Template { "" }
            },
    )

private object HeaderTestRestDocumentationContext : RestDocumentationContext {
  override fun getTestClass(): Class<*> = HeaderCompilerTest::class.java

  override fun getTestMethodName(): String = "headerSnippet"

  override fun getStepCount(): Int = 0

  override fun getOutputDirectory(): File = File("build/generated-snippets")
}

private enum class CountingHeaderRole(private val serializedValue: String) {
  USER("user"),
  ADMIN("admin");

  @com.fasterxml.jackson.annotation.JsonValue
  fun value(): String {
    serializationCount += 1
    return serializedValue
  }

  companion object {
    var serializationCount: Int = 0
  }
}
