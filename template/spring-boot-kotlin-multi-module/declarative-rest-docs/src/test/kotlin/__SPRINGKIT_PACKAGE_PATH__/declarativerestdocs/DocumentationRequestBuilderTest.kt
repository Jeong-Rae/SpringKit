package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockServletContext
import tools.jackson.databind.ObjectMapper

class DocumentationRequestBuilderTest :
    FunSpec({
      val objectMapper = ObjectMapper()
      val requestBuilder = DocumentationRequestBuilder(objectMapper)

      context("Documentation sample 기반 요청 생성") {
        test("모든 요청 context를 선언하면, sample로 HTTP 요청을 생성합니다") {
          val documentation =
              documentationDefinition("create-user") {
                summary = "사용자 생성"
                description = "사용자를 생성합니다."
                requestLine("post", "/users/{userId}") {
                  pathVariable("userId", "사용자 식별자", sample = "user-123")
                  queryParameter("dryRun", "검증 여부", sample = false)
                  queryParameter("tag", "사용자 태그", sample = listOf("spring", "java"))
                  queryParameter("rating", "평점", sample = arrayOf(1, 2))
                }
                requestHeader {
                  header("X-Request-Id", "요청 식별자", sample = "request-123")
                }
                requestBody {
                  field("name", "사용자 이름", sample = "Alice")
                  field("profile.nickname", "사용자 별명", sample = "ally")
                  field("members[].id", "구성원 식별자", sample = "member-1")
                }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())
          val body = objectMapper.readTree(request.contentAsByteArray)

          request.method shouldBe "POST"
          request.requestURI shouldBe "/users/user-123"
          request.getParameter("dryRun") shouldBe "false"
          requireNotNull(request.getParameterValues("tag")).toList() shouldBe
              listOf("spring", "java")
          requireNotNull(request.getParameterValues("rating")).toList() shouldBe listOf("1", "2")
          request.getHeader("X-Request-Id") shouldBe "request-123"
          request.contentType shouldBe "application/json"
          body.at("/name").stringValue() shouldBe "Alice"
          body.at("/profile/nickname").stringValue() shouldBe "ally"
          body.at("/members/0/id").stringValue() shouldBe "member-1"
        }

        test("반복 query sample의 null 원소를 거부합니다") {
          val documentation =
              documentationDefinition("invalid-query") {
                summary = "잘못된 질의 값"
                description = "null 원소가 있는 질의 값을 거부합니다."
                requestLine("get", "/users") {
                  queryParameter("tag", "사용자 태그", sample = listOf("spring", null))
                }
              }

          shouldThrow<IllegalArgumentException> { requestBuilder.build(documentation) }
        }

        test("Content-Type header를 실제 JSON 요청에 반영합니다") {
          val documentation =
              documentationDefinition("patch-user") {
                summary = "사용자 부분 수정"
                description = "사용자 일부 정보를 수정합니다."
                requestLine("patch", "/users/1")
                requestHeader {
                  header(
                      "Content-Type",
                      "JSON Merge Patch Content-Type",
                      sample = "application/merge-patch+json",
                  )
                }
                requestBody {
                  field("name", "사용자 이름", sample = "Alice")
                }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())
          val body = objectMapper.readTree(request.contentAsByteArray)

          request.contentType shouldBe "application/merge-patch+json"
          body.at("/name").stringValue() shouldBe "Alice"
        }
      }
    })
