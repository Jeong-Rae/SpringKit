package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod

class DocumentationTest :
    FunSpec({
      context("PathVariable의 입력값") {
        test("PathVariable을 생성하면, 입력값을 보존한다") {
          val pathVariable =
              PathVariable(
                  key = "tenantId",
                  description = "테넌트 식별자",
                  sample = sampleOf("tenant-1"),
              )

          pathVariable.key shouldBe "tenantId"
          pathVariable.description shouldBe "테넌트 식별자"
          pathVariable.sample.value shouldBe "tenant-1"
        }
      }

      context("값 요소의 선택 및 제외 상태") {
        withData(
            nameFn = { it.name },
            valueElementStateCases(),
        ) { case ->
          case.actualOptional shouldBe case.expectedOptional
          case.actualIgnored shouldBe case.expectedIgnored
        }
      }

      context("HTTP context의 선언 순서") {
        test("여러 요소와 tags를 입력하면, 모든 선언 순서를 유지한다") {
          val firstPath = PathVariable("tenantId", "테넌트 식별자", sampleOf("tenant-1"))
          val secondPath = PathVariable("userId", "사용자 식별자", sampleOf("user-1"))
          val firstQuery = QueryParameter("dryRun", "검증 여부", sampleOf(false))
          val secondQuery = QueryParameter("verbose", "상세 여부", sampleOf(true))
          val firstHeader = Header("X-Request-Id", "요청 식별자", sampleOf("request-1"))
          val secondHeader = Header("X-Trace-Id", "추적 식별자", sampleOf("trace-1"))
          val firstField = Field("name", "사용자 이름", sampleOf("Alice"))
          val secondField = Field("role", "사용자 역할", sampleOf("ADMIN"))

          val documentation =
              Documentation(
                  name = "create-user",
                  summary = "사용자 생성",
                  description = "새로운 사용자를 생성한다.",
                  tags = linkedSetOf("users", "admin"),
                  requestLine =
                      RequestLine(
                          method = HttpMethod.POST,
                          uri = "/tenants/{tenantId}/users/{userId}",
                          pathVariables = listOf(firstPath, secondPath),
                          queryParameters = listOf(firstQuery, secondQuery),
                      ),
                  requestHeaders = Headers(listOf(firstHeader, secondHeader)),
                  requestBody = Body(listOf(firstField, secondField)),
              )

          documentation.requestLine.pathVariables shouldBe listOf(firstPath, secondPath)
          documentation.requestLine.queryParameters shouldBe listOf(firstQuery, secondQuery)
          documentation.requestHeaders.headers shouldBe listOf(firstHeader, secondHeader)
          documentation.requestBody.fields shouldBe listOf(firstField, secondField)
          documentation.tags.toList() shouldBe listOf("users", "admin")
        }
      }
    })

private fun valueElementStateCases(): List<ValueElementStateCase> =
    listOf(
        QueryParameter("dryRun", "검증만 수행할지 여부", sampleOf(false), optional = true).let {
          ValueElementStateCase(
              name = "QueryParameter의 optional이 true이면, 선택 상태를 유지한다",
              actualOptional = it.optional,
              actualIgnored = it.ignored,
              expectedOptional = true,
              expectedIgnored = false,
          )
        },
        Field("profile.nickname", "사용자 별명", sampleOf("Alice"), optional = true).let {
          ValueElementStateCase(
              name = "Field의 optional이 true이면, 선택 상태를 유지한다",
              actualOptional = it.optional,
              actualIgnored = it.ignored,
              expectedOptional = true,
              expectedIgnored = false,
          )
        },
        Header("X-Debug", "디버그 정보 포함 여부", sampleOf(false), ignored = true).let {
          ValueElementStateCase(
              name = "Header의 ignored가 true이면, 제외 상태를 유지한다",
              actualOptional = it.optional,
              actualIgnored = it.ignored,
              expectedOptional = false,
              expectedIgnored = true,
          )
        },
        Field("legacyCode", "이전 시스템 코드", sampleOf("legacy"), ignored = true).let {
          ValueElementStateCase(
              name = "Field의 ignored가 true이면, 제외 상태를 유지한다",
              actualOptional = it.optional,
              actualIgnored = it.ignored,
              expectedOptional = false,
              expectedIgnored = true,
          )
        },
    )

private data class ValueElementStateCase(
    val name: String,
    val actualOptional: Boolean,
    val actualIgnored: Boolean,
    val expectedOptional: Boolean,
    val expectedIgnored: Boolean,
)
