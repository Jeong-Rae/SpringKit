package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.spring

import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.DeclarativeRestDocsTest
import org.junit.jupiter.api.Test

class CreateUserDocumentationTest : DeclarativeRestDocsTest() {

  @Test
  fun createUser() {
    documentation("create-user") {
      summary = "사용자 생성"
      description = "테넌트에 새로운 사용자를 생성합니다."
      tags("users")

      requestLine("post", "/tenants/{tenantId}/users") {
        pathVariable("tenantId", "테넌트 식별자", sample = "tenant-1")
        queryParameter("dryRun", "검증만 수행할지 여부", sample = false)
      }

      requestHeader {
        header("X-Request-Id", "요청 추적 식별자", sample = "request-123")
      }

      requestBody {
        field("name", "사용자 이름", sample = "Alice")
        field("role", "사용자 역할", sample = UserRole.ADMIN)
      }

      responseBody {
        field("id", "사용자 식별자", sample = "user-123")
      }
    }
  }
}
