package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

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
          request.getHeader("X-Request-Id") shouldBe "request-123"
          request.contentType shouldBe "application/json"
          body.at("/name").stringValue() shouldBe "Alice"
          body.at("/profile/nickname").stringValue() shouldBe "ally"
          body.at("/members/0/id").stringValue() shouldBe "member-1"
        }
      }
    })
