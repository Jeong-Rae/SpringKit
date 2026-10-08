package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.spring

import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.DeclarativeRestDocsTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders

@SpringBootTest(classes = [SpringFixtureApplication::class])
class SpringHttpFixtureDocumentationTest : DeclarativeRestDocsTest() {

  @Test
  fun getMembers() {
    documentation("get-members") {
      summary = "회원 목록 조회"
      description = "회원 목록을 조회합니다."
      tags("members")
      requestLine("get", "/api/members") {
        queryParameter("page", "페이지 번호", sample = 0)
        queryParameter("size", "페이지 크기", sample = 20)
        queryParameter("status", "회원 상태", sample = "ACTIVE")
        queryParameter("tag", "회원 태그", sample = listOf("spring", "java"))
      }
      requestHeader {
        header(HttpHeaders.AUTHORIZATION, "Bearer 인증 토큰", sample = "Bearer test-token")
        header("X-Request-Id", "요청 추적 식별자", sample = "req-001")
      }
      responseBody { memberPageFields() }
    }
  }

  @Test
  fun getMember() {
    documentation("get-member") {
      summary = "회원 단건 조회"
      description = "회원 식별자로 회원을 조회합니다."
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
    documentation("create-member") {
      summary = "회원 생성"
      description = "JSON 요청으로 회원을 생성합니다."
      tags("members")
      requestLine("post", "/api/members")
      requestHeader {
        header("Idempotency-Key", "멱등성 식별자", sample = "create-member-001")
      }
      requestBody {
        memberWriteFields("Jane", "jane@example.com", true, 30, listOf("java", "spring"))
      }
      responseHeader {
        header(HttpHeaders.LOCATION, "생성된 회원 URI", sample = "/api/members/1")
      }
      responseBody { memberFields() }
    }
  }

  @Test
  fun replaceMember() {
    documentation("replace-member") {
      summary = "회원 전체 수정"
      description = "If-Match 조건으로 회원 전체 정보를 수정합니다."
      tags("members")
      requestLine("put", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
      requestHeader {
        header(HttpHeaders.IF_MATCH, "수정 전 ETag", sample = "\"member-1-v1\"")
      }
      requestBody {
        memberWriteFields("Jane Doe", "jane.doe@example.com", false, 31, emptyList())
      }
      responseHeader {
        header(HttpHeaders.ETAG, "수정 후 ETag", sample = "\"member-1-v2\"")
      }
      responseBody { memberFields() }
    }
  }

  @Test
  fun patchMember() {
    documentation("patch-member") {
      summary = "회원 부분 수정"
      description = "JSON Merge Patch로 회원 일부 정보를 수정합니다."
      tags("members")
      requestLine("patch", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
      requestHeader {
        header(
            HttpHeaders.CONTENT_TYPE,
            "JSON Merge Patch Content-Type",
            sample = "application/merge-patch+json",
        )
      }
      requestBody {
        field("name", "변경할 회원 이름", sample = "Jane Smith")
      }
      responseBody {
        field("id", "회원 식별자", sample = 1L)
        field("name", "회원 이름", sample = "Jane Smith")
      }
    }
  }

  @Test
  fun deleteMember() {
    documentation("delete-member") {
      summary = "회원 삭제"
      description = "회원을 삭제하고 204 응답을 반환합니다."
      tags("members")
      requestLine("delete", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
    }
  }

  @Test
  fun headMember() {
    documentation("head-member") {
      summary = "회원 HEAD 조회"
      description = "회원 리소스의 응답 헤더만 조회합니다."
      tags("members")
      requestLine("head", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
    }
  }

  @Test
  fun optionsMember() {
    documentation("options-member") {
      summary = "회원 OPTIONS 조회"
      description = "회원 리소스가 허용하는 HTTP 메서드를 조회합니다."
      tags("members")
      requestLine("options", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
      responseHeader {
        header(HttpHeaders.ALLOW, "허용 HTTP 메서드", sample = "GET,HEAD,PUT,PATCH,DELETE,OPTIONS")
      }
    }
  }
}

private fun BodyDsl.memberPageFields() {
  field("content[].id", "회원 식별자", sample = 1L)
  field("content[].name", "회원 이름", sample = "Jane")
  field("content[].email", "회원 이메일", sample = "jane@example.com")
  field("content[].active", "활성 여부", sample = true)
  field("content[].profile.age", "회원 나이", sample = 30)
  field("content[].profile.tags", "회원 태그", sample = listOf("java", "spring"))
  field("page", "페이지 번호", sample = 0)
  field("size", "페이지 크기", sample = 20)
}

private fun BodyDsl.memberFields() {
  field("id", "회원 식별자", sample = 1L)
  field("name", "회원 이름", sample = "Jane")
  field("email", "회원 이메일", sample = "jane@example.com")
  field("active", "활성 여부", sample = true)
  field("profile.age", "회원 나이", sample = 30)
  field("profile.tags", "회원 태그", sample = listOf("java", "spring"))
}

private fun BodyDsl.memberWriteFields(
    name: String,
    email: String,
    active: Boolean,
    age: Int,
    tags: List<String>,
) {
  field("name", "회원 이름", sample = name)
  field("email", "회원 이메일", sample = email)
  field("active", "활성 여부", sample = active)
  field("profile.age", "회원 나이", sample = age)
  field("profile.tags", "회원 태그", sample = tags)
}
