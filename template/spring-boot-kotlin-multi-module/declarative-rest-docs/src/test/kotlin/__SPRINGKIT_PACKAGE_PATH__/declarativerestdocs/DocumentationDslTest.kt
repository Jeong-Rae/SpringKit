package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf
import org.springframework.http.HttpMethod

class DocumentationDslTest :
    FunSpec({
      context("Documentation DSL의 Core 모델 변환") {
        test("모든 context를 선언하면, 값과 타입 및 선언 순서를 보존합니다") {
          val actual =
              documentationDefinition("create-user") {
                summary = "사용자 생성"
                description = "새로운 사용자를 생성합니다."
                tags("users", "admin")

                requestLine(method = "post", path = "/tenants/{tenantId}/users") {
                  pathVariable("tenantId", "테넌트 식별자", sample = "tenant-1")
                  queryParameter("dryRun", "검증만 수행할지 여부", sample = false, optional = true)
                  ignoredQueryParameter("legacy", "이전 질의 값", sample = "legacy")
                }
                requestHeader {
                  header("X-Request-Id", "요청 추적 식별자", sample = "request-123")
                  ignoredHeader("X-Trace-Id", "요청 로깅 식별자", sample = "trace-999")
                }
                requestBody {
                  field("name", "사용자 이름", sample = "Alice")
                  field("nickname", "사용자 별명", sample = "ally", optional = true)
                  ignoredField("legacyCode", "이전 코드", sample = "legacy")
                }
                responseHeader {
                  header("Location", "생성된 사용자 URI", sample = "/users/1")
                }
                responseBody {
                  field("id", "사용자 식별자", sample = "user-123")
                  field<UserRole>("role", "사용자 역할", sample = UserRole.ADMIN)
                  field("permissions", "사용자 권한", sample = listOf("WRITE"))
                }
              }

          actual.name shouldBe "create-user"
          actual.summary shouldBe "사용자 생성"
          actual.description shouldBe "새로운 사용자를 생성합니다."
          actual.tags.toList() shouldBe listOf("users", "admin")
          actual.requestLine.method shouldBe HttpMethod.POST
          actual.requestLine.uri shouldBe "/tenants/{tenantId}/users"
          actual.requestLine.pathVariables.map(ValueElement::key) shouldBe listOf("tenantId")
          actual.requestLine.queryParameters.map(ValueElement::key) shouldBe
              listOf("dryRun", "legacy")
          actual.requestLine.queryParameters.map(QueryParameter::optional) shouldBe
              listOf(true, false)
          actual.requestLine.queryParameters.map(QueryParameter::ignored) shouldBe
              listOf(false, true)
          actual.requestHeaders.headers.map(ValueElement::key) shouldBe
              listOf("X-Request-Id", "X-Trace-Id")
          actual.requestHeaders.headers.map(Header::ignored) shouldBe listOf(false, true)
          actual.requestBody.fields.map(ValueElement::key) shouldBe
              listOf("name", "nickname", "legacyCode")
          actual.requestBody.fields.map(Field::optional) shouldBe listOf(false, true, false)
          actual.requestBody.fields.map(Field::ignored) shouldBe listOf(false, false, true)
          actual.responseHeaders.headers.map(ValueElement::key) shouldBe listOf("Location")
          actual.responseBody.fields.map(ValueElement::key) shouldBe
              listOf("id", "role", "permissions")
          actual.responseBody.fields[1].sample.value shouldBe UserRole.ADMIN
          actual.responseBody.fields[1].sample.type shouldBe typeOf<UserRole>()
          actual.responseBody.fields[2].sample.type shouldBe typeOf<List<String>>()
        }

        test("선택 context를 생략하면, 빈 header와 body를 생성합니다") {
          val actual =
              documentationDefinition("health") {
                summary = "상태 확인"
                description = "서비스 상태를 확인합니다."
                requestLine(method = "get", path = "/health")
              }

          actual.requestHeaders shouldBe Headers()
          actual.requestBody shouldBe Body()
          actual.responseHeaders shouldBe Headers()
          actual.responseBody shouldBe Body()
        }
      }

      context("Documentation DSL의 필수 선언") {
        test("summary를 생략하면, 문서 생성을 거부합니다") {
          shouldThrow<IllegalStateException> {
            documentationDefinition("health") {
              description = "서비스 상태를 확인합니다."
              requestLine(method = "get", path = "/health")
            }
          }
        }

        test("description을 생략하면, 문서 생성을 거부합니다") {
          shouldThrow<IllegalStateException> {
            documentationDefinition("health") {
              summary = "상태 확인"
              requestLine(method = "get", path = "/health")
            }
          }
        }

        test("requestLine을 생략하면, 문서 생성을 거부합니다") {
          shouldThrow<IllegalStateException> {
            documentationDefinition("health") {
              summary = "상태 확인"
              description = "서비스 상태를 확인합니다."
            }
          }
        }

        test("requestLine을 두 번 선언하면, 문서 생성을 거부합니다") {
          shouldThrow<IllegalStateException> {
            documentationDefinition("health") {
              summary = "상태 확인"
              description = "서비스 상태를 확인합니다."
              requestLine(method = "get", path = "/health")
              requestLine(method = "get", path = "/health/readiness")
            }
          }
        }
      }
    })

private enum class UserRole {
  ADMIN
}
