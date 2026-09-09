package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import kotlin.reflect.typeOf

@OptIn(ExperimentalStdlibApi::class)
class CoreDocumentationFixtureTest :
    FunSpec({
        context("create-user Core fixture 계약") {
            test("모든 v1 HTTP context와 값 상태를 표현한다") {
                val documentation = createUserDocumentation()

                documentation.name shouldBe "create-user"
                documentation.summary shouldBe "사용자 생성"
                documentation.description shouldBe "새로운 사용자를 생성한다."
                documentation.tags shouldBe setOf("users")

                documentation.requestLine.method shouldBe HttpMethod.POST
                documentation.requestLine.uri shouldBe "/tenants/{tenantId}/users"
                documentation.requestLine.pathVariables.map { it.key } shouldBe listOf("tenantId")
                documentation.requestLine.queryParameters.map { it.key } shouldBe listOf("dryRun", "debug")
                documentation.requestLine.queryParameters.first().optional shouldBe true
                documentation.requestLine.queryParameters.last().ignored shouldBe true

                documentation.requestHeaders.headers.map { it.key } shouldBe
                    listOf("X-Request-Id", "X-Internal-Trace")
                documentation.requestHeaders.headers.first().ignored shouldBe false
                documentation.requestHeaders.headers.last().ignored shouldBe true

                documentation.requestBody.fields.map { it.key } shouldBe
                    listOf("name", "profile.nickname", "roles", "role", "legacyCode")
                documentation.requestBody.fields[1].optional shouldBe true
                documentation.requestBody.fields[2].sample.type shouldBe typeOf<List<FixtureUserRole>>()
                documentation.requestBody.fields[3].sample.type shouldBe typeOf<FixtureUserRole>()
                documentation.requestBody.fields[4].ignored shouldBe true

                documentation.responseHeaders.headers.map { it.key } shouldBe listOf("Location")
                documentation.responseBody.fields.map { it.key } shouldBe listOf("id", "name", "role")
            }
        }
    })

private fun createUserDocumentation(): Documentation =
    Documentation(
        name = "create-user",
        summary = "사용자 생성",
        description = "새로운 사용자를 생성한다.",
        tags = setOf("users"),
        requestLine =
            RequestLine(
                method = HttpMethod.POST,
                uri = "/tenants/{tenantId}/users",
                pathVariables =
                    listOf(
                        PathVariable(
                            key = "tenantId",
                            description = "사용자를 생성할 테넌트 식별자",
                            sample = sampleOf("tenant-1"),
                        ),
                    ),
                queryParameters =
                    listOf(
                        QueryParameter(
                            key = "dryRun",
                            description = "사용자 생성 검증만 수행할지 여부",
                            sample = sampleOf(false),
                            optional = true,
                        ),
                        QueryParameter(
                            key = "debug",
                            description = "내부 디버그 정보 포함 여부",
                            sample = sampleOf(false),
                            ignored = true,
                        ),
                    ),
            ),
        requestHeaders =
            Headers(
                listOf(
                    Header(
                        key = "X-Request-Id",
                        description = "요청 추적 식별자",
                        sample = sampleOf("request-123"),
                    ),
                    Header(
                        key = "X-Internal-Trace",
                        description = "내부 추적 식별자",
                        sample = sampleOf("trace-123"),
                        ignored = true,
                    ),
                ),
            ),
        requestBody =
            Body(
                listOf(
                    Field("name", "사용자 이름", sampleOf("Alice")),
                    Field(
                        key = "profile.nickname",
                        description = "사용자 별명",
                        sample = sampleOf("ally"),
                        optional = true,
                    ),
                    Field(
                        key = "roles",
                        description = "사용자 역할 목록",
                        sample = sampleOf(listOf(FixtureUserRole.USER, FixtureUserRole.ADMIN)),
                    ),
                    Field("role", "대표 사용자 역할", sampleOf(FixtureUserRole.ADMIN)),
                    Field(
                        key = "legacyCode",
                        description = "이전 시스템 코드",
                        sample = sampleOf("legacy"),
                        ignored = true,
                    ),
                ),
            ),
        responseHeaders =
            Headers(
                listOf(
                    Header(
                        key = "Location",
                        description = "생성된 사용자 URI",
                        sample = sampleOf("/tenants/tenant-1/users/user-123"),
                    ),
                ),
            ),
        responseBody =
            Body(
                listOf(
                    Field("id", "생성된 사용자 식별자", sampleOf("user-123")),
                    Field("name", "사용자 이름", sampleOf("Alice")),
                    Field("role", "사용자 역할", sampleOf(FixtureUserRole.ADMIN)),
                ),
            ),
    )

private enum class FixtureUserRole {
    USER,
    ADMIN,
}
