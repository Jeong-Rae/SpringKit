package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.HttpMethod
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreDocumentationFixtureTest {

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `create-user fixture가 v1 Core 입력 범위를 표현한다`() {
        val documentation = createUserDocumentation()

        assertEquals("create-user", documentation.name)
        assertEquals("사용자 생성", documentation.summary)
        assertEquals("새로운 사용자를 생성한다.", documentation.description)
        assertEquals(setOf("users"), documentation.tags)

        assertEquals(HttpMethod.POST, documentation.requestLine.method)
        assertEquals("/tenants/{tenantId}/users", documentation.requestLine.uri)
        assertEquals(listOf("tenantId"), documentation.requestLine.pathVariables.map { it.key })
        assertEquals(listOf("dryRun", "debug"), documentation.requestLine.queryParameters.map { it.key })
        assertTrue(documentation.requestLine.queryParameters.first().optional)
        assertTrue(documentation.requestLine.queryParameters.last().ignored)

        assertEquals(
            listOf("X-Request-Id", "X-Internal-Trace"),
            documentation.requestHeaders.headers.map { it.key },
        )
        assertFalse(documentation.requestHeaders.headers.first().ignored)
        assertTrue(documentation.requestHeaders.headers.last().ignored)

        assertEquals(
            listOf("name", "profile.nickname", "roles", "role", "legacyCode"),
            documentation.requestBody.fields.map { it.key },
        )
        assertTrue(documentation.requestBody.fields[1].optional)
        assertEquals(typeOf<List<UserRole>>(), documentation.requestBody.fields[2].sample.type)
        assertEquals(typeOf<UserRole>(), documentation.requestBody.fields[3].sample.type)
        assertTrue(documentation.requestBody.fields[4].ignored)

        assertEquals(listOf("Location"), documentation.responseHeaders.headers.map { it.key })
        assertEquals(listOf("id", "name", "role"), documentation.responseBody.fields.map { it.key })
    }

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
                            sample = sampleOf(listOf(UserRole.USER, UserRole.ADMIN)),
                        ),
                        Field("role", "대표 사용자 역할", sampleOf(UserRole.ADMIN)),
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
                        Field("role", "사용자 역할", sampleOf(UserRole.ADMIN)),
                    ),
                ),
        )

    private enum class UserRole {
        USER,
        ADMIN,
    }
}
