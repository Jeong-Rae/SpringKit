package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.restdocs.generate.RestDocumentationGenerator
import org.springframework.restdocs.operation.OperationRequestFactory
import org.springframework.restdocs.operation.OperationResponseFactory
import org.springframework.restdocs.operation.StandardOperation
import org.springframework.restdocs.snippet.SnippetException
import tools.jackson.databind.ObjectMapper

class RequestLineCompilerTest :
    FunSpec({
      val compiler =
          RequestLineCompiler(
              ParameterCompiler(
                  ValueMetadataResolver(ObjectMapper()),
              )
          )

      context("Request line 컴파일") {
        test("Path와 query parameter를 컴파일하면, 종류별 선언 순서를 유지합니다") {
          val compiled =
              compiler.compile(
                  RequestLine(
                      method = HttpMethod.GET,
                      uri = "/users/{userId}",
                      pathVariables =
                          listOf(
                              PathVariable("userId", "사용자 ID", sampleOf(1L)),
                              PathVariable("postId", "게시물 ID", sampleOf(2L)),
                          ),
                      queryParameters =
                          listOf(
                              QueryParameter("page", "페이지", sampleOf(1)),
                              QueryParameter(
                                  key = "debug",
                                  description = "디버깅 여부",
                                  sample = sampleOf(false),
                                  ignored = true,
                              ),
                          ),
                  )
              )

          compiled.pathParameters.map { it.descriptor.name } shouldContainExactly
              listOf("userId", "postId")
          compiled.queryParameters.map { it.descriptor.name } shouldContainExactly
              listOf("page", "debug")
          compiled.queryParameters.last().descriptor.isIgnored shouldBe true
        }
      }

      context("Request line 검증") {
        test("method와 URI template이 일치하면, 검증에 성공합니다") {
          val snippet =
              compiler.compile(RequestLine(HttpMethod.GET, "/users/{userId}")).validationSnippet

          shouldNotThrowAny {
            snippet.document(
                requestLineOperation(
                    method = HttpMethod.GET,
                    uriTemplate = "/users/{userId}",
                )
            )
          }
        }

        test("method가 다르면, 기대값과 실제값을 포함한 오류가 발생합니다") {
          val snippet =
              compiler.compile(RequestLine(HttpMethod.POST, "/users/{userId}")).validationSnippet

          val exception =
              shouldThrow<SnippetException> {
                snippet.document(
                    requestLineOperation(
                        method = HttpMethod.GET,
                        uriTemplate = "/users/{userId}",
                    )
                )
              }

          exception.message shouldBe "HTTP method가 일치하지 않습니다. expected=POST, actual=GET"
        }

        test("URI template이 다르면, 기대값과 실제값을 포함한 오류가 발생합니다") {
          val snippet =
              compiler.compile(RequestLine(HttpMethod.GET, "/users/{userId}")).validationSnippet

          val exception =
              shouldThrow<SnippetException> {
                snippet.document(
                    requestLineOperation(
                        method = HttpMethod.GET,
                        uriTemplate = "/members/{userId}",
                    )
                )
              }

          exception.message shouldBe
              "URI template이 일치하지 않습니다. expected=/users/{userId}, actual=/members/{userId}"
        }

        test("URI template attribute가 없으면, actual이 null인 오류가 발생합니다") {
          val snippet =
              compiler.compile(RequestLine(HttpMethod.GET, "/users/{userId}")).validationSnippet

          val exception =
              shouldThrow<SnippetException> {
                snippet.document(
                    requestLineOperation(
                        method = HttpMethod.GET,
                        uriTemplate = null,
                    )
                )
              }

          exception.message shouldBe
              "URI template이 일치하지 않습니다. expected=/users/{userId}, actual=null"
        }
      }
    })

private fun requestLineOperation(
    method: HttpMethod,
    uriTemplate: String?,
): StandardOperation {
  val request =
      OperationRequestFactory()
          .create(
              URI.create("https://example.com/users/1"),
              method,
              ByteArray(0),
              HttpHeaders(),
              emptyList(),
          )
  val response = OperationResponseFactory().create(HttpStatus.OK, HttpHeaders(), ByteArray(0))
  val attributes =
      if (uriTemplate == null) {
        emptyMap()
      } else {
        mapOf(RestDocumentationGenerator.ATTRIBUTE_NAME_URL_TEMPLATE to uriTemplate)
      }

  return StandardOperation(
      "request-line",
      request,
      response,
      attributes,
  )
}
