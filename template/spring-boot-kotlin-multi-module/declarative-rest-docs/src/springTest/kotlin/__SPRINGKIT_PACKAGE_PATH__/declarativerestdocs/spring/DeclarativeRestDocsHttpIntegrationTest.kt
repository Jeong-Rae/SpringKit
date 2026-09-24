package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.spring

import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.DeclarativeRestDocsTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class DeclarativeRestDocsHttpIntegrationTest : DeclarativeRestDocsTest() {

  @Test
  fun getMember() {
    documentation("declarative-get-member") {
      summary = "회원 단건 조회"
      description = "DeclarativeRestDocsTest가 실제 Spring MVC Controller를 호출하는 경로를 검증합니다."
      tags("members")

      requestLine("get", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }

      requestHeader {
        header(HttpHeaders.COOKIE, "세션 쿠키", sample = "SESSION=session-token")
      }

      responseBody { memberFields() }
    }
  }

  @Test
  fun createMember() {
    documentation("declarative-create-member") {
      summary = "회원 생성"
      description = "DSL sample로 실제 JSON 요청을 생성하고 Controller 응답과 REST Docs 산출물을 검증합니다."
      tags("members")

      requestLine("post", "/api/members")

      requestHeader {
        header("Idempotency-Key", "멱등성 식별자", sample = "create-member-001")
      }

      requestBody {
        field("name", "회원 이름", sample = "Jane")
        field("email", "회원 이메일", sample = "jane@example.com")
        field("active", "활성 여부", sample = true)
        field("profile.age", "회원 나이", sample = 30)
        field("profile.tags", "회원 태그", sample = listOf("java", "spring"))
      }

      responseHeader {
        header(HttpHeaders.LOCATION, "생성된 회원 URI", sample = "/api/members/1")
      }

      responseBody { memberFields() }
    }
  }
}

private fun BodyDsl.memberFields() {
  field("id", "회원 식별자", sample = 1L)
  field("name", "회원 이름", sample = "Jane")
  field("email", "회원 이메일", sample = "jane@example.com")
  field("active", "활성 여부", sample = true)
  field("profile.age", "회원 나이", sample = 30)
  field("profile.tags", "회원 태그", sample = listOf("java", "spring"))
}
