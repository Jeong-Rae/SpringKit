package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentationTest {

    @Test
    fun `path variable은 required 상태로 유지한다`() {
        val pathVariable =
            PathVariable(
                key = "tenantId",
                description = "테넌트 식별자",
                sample = sampleOf("tenant-1"),
            )

        assertEquals("tenantId", pathVariable.key)
        assertEquals("테넌트 식별자", pathVariable.description)
        assertEquals("tenant-1", pathVariable.sample.value)
    }

    @Test
    fun `값 요소의 optional과 ignored 상태를 유지한다`() {
        val queryParameter =
            QueryParameter(
                key = "dryRun",
                description = "검증만 수행할지 여부",
                sample = sampleOf(false),
                optional = true,
            )
        val header =
            Header(
                key = "X-Debug",
                description = "디버그 정보 포함 여부",
                sample = sampleOf(false),
                ignored = true,
            )
        val field =
            Field(
                key = "profile.nickname",
                description = "사용자 별명",
                sample = sampleOf("Alice"),
                optional = true,
                ignored = true,
            )

        assertTrue(queryParameter.optional)
        assertFalse(queryParameter.ignored)
        assertFalse(header.optional)
        assertTrue(header.ignored)
        assertTrue(field.optional)
        assertTrue(field.ignored)
    }

    @Test
    fun `HTTP context의 선언 순서를 유지한다`() {
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

        assertEquals(listOf(firstPath, secondPath), documentation.requestLine.pathVariables)
        assertEquals(listOf(firstQuery, secondQuery), documentation.requestLine.queryParameters)
        assertEquals(listOf(firstHeader, secondHeader), documentation.requestHeaders.headers)
        assertEquals(listOf(firstField, secondField), documentation.requestBody.fields)
        assertEquals(listOf("users", "admin"), documentation.tags.toList())
    }
}
