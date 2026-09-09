package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod

class DocumentationTest :
    FunSpec({
        context("PathVariable의 필수 상태 계약") {
            test("PathVariable을 생성하면 필수 상태를 유지한다") {
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

        context("값 요소의 선택 및 제외 상태 계약") {
            test("optional이 true이면 선택 상태를 유지한다") {
                val queryParameter =
                    QueryParameter(
                        key = "dryRun",
                        description = "검증만 수행할지 여부",
                        sample = sampleOf(false),
                        optional = true,
                    )
                val field =
                    Field(
                        key = "profile.nickname",
                        description = "사용자 별명",
                        sample = sampleOf("Alice"),
                        optional = true,
                    )

                queryParameter.optional shouldBe true
                queryParameter.ignored shouldBe false
                field.optional shouldBe true
                field.ignored shouldBe false
            }

            test("ignored가 true이면 제외 상태를 유지한다") {
                val header =
                    Header(
                        key = "X-Debug",
                        description = "디버그 정보 포함 여부",
                        sample = sampleOf(false),
                        ignored = true,
                    )
                val field =
                    Field(
                        key = "legacyCode",
                        description = "이전 시스템 코드",
                        sample = sampleOf("legacy"),
                        ignored = true,
                    )

                header.optional shouldBe false
                header.ignored shouldBe true
                field.optional shouldBe false
                field.ignored shouldBe true
            }
        }

        context("HTTP context의 선언 순서 계약") {
            test("여러 요소와 tags를 입력하면 선언 순서를 유지한다") {
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
