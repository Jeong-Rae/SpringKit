package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceDocumentation.headerWithName as resourceHeaderWithName
import com.epages.restdocs.apispec.ResourceDocumentation.parameterWithName as resourceParameterWithName
import com.epages.restdocs.apispec.ResourceDocumentation.resource
import com.epages.restdocs.apispec.ResourceSnippetParameters
import com.epages.restdocs.apispec.SimpleType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.snippet.SnippetException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class CreateUserDocumentationTest :
    FunSpec({
        lateinit var mockMvc: MockMvc
        lateinit var restDocumentation: ManualRestDocumentation

        beforeTest { testCase ->
            restDocumentation = ManualRestDocumentation()
            restDocumentation.beforeTest(
                CreateUserDocumentationTest::class.java,
                testCase.name.name,
            )
            mockMvc =
                standaloneSetup(CreateUserController())
                    .apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
                    .build()
        }

        afterTest {
            restDocumentation.afterTest()
        }

        context("create-user 문서 생성") {
            test("요청과 응답을 문서화하면, 기준 문서 조각을 생성한다") {
                val requestFieldDescriptors =
                    listOf(
                        fieldWithPath("name").type(JsonFieldType.STRING).description("사용자 이름"),
                        fieldWithPath("role").type(JsonFieldType.STRING).description("사용자 역할"),
                    )
                val responseFieldDescriptors =
                    listOf(
                        fieldWithPath("id").type(JsonFieldType.STRING).description("생성된 사용자 식별자"),
                        fieldWithPath("name").type(JsonFieldType.STRING).description("사용자 이름"),
                        fieldWithPath("role").type(JsonFieldType.STRING).description("사용자 역할"),
                    )

                mockMvc
                    .perform(createUserRequest())
                    .andExpect(status().isCreated)
                    .andExpect(
                        header().string(HttpHeaders.LOCATION, "/tenants/tenant-1/users/user-123")
                    )
                    .andExpect(jsonPath("$.id").value("user-123"))
                    .andDo(
                        document(
                            "create-user",
                            pathParameters(
                                parameterWithName("tenantId").description("사용자를 생성할 테넌트 식별자")
                            ),
                            queryParameters(
                                parameterWithName("dryRun").description("사용자 생성 검증만 수행할지 여부")
                            ),
                            requestHeaders(headerWithName("X-Request-Id").description("요청 추적 식별자")),
                            requestFields(requestFieldDescriptors),
                            responseHeaders(
                                headerWithName(HttpHeaders.LOCATION).description("생성된 사용자 URI")
                            ),
                            responseFields(responseFieldDescriptors),
                            resource(
                                ResourceSnippetParameters.builder()
                                    .summary("사용자 생성")
                                    .description("테넌트에 새로운 사용자를 생성합니다.")
                                    .tag("users")
                                    .pathParameters(
                                        resourceParameterWithName("tenantId")
                                            .type(SimpleType.STRING)
                                            .description("사용자를 생성할 테넌트 식별자")
                                    )
                                    .queryParameters(
                                        resourceParameterWithName("dryRun")
                                            .type(SimpleType.BOOLEAN)
                                            .description("사용자 생성 검증만 수행할지 여부")
                                    )
                                    .requestHeaders(
                                        resourceHeaderWithName("X-Request-Id")
                                            .type(SimpleType.STRING)
                                            .description("요청 추적 식별자")
                                    )
                                    .requestFields(requestFieldDescriptors)
                                    .responseHeaders(
                                        resourceHeaderWithName(HttpHeaders.LOCATION)
                                            .type(SimpleType.STRING)
                                            .description("생성된 사용자 URI")
                                    )
                                    .responseFields(responseFieldDescriptors)
                                    .build()
                            ),
                        )
                    )
            }
        }

        context("create-user field descriptor 검증") {
            test("request field가 descriptor와 다르면, 문서 생성에 실패한다") {
                shouldThrow<SnippetException> {
                    mockMvc
                        .perform(createUserRequest())
                        .andDo(
                            document(
                                "create-user-request-mismatch",
                                requestFields(fieldWithPath("name").description("사용자 이름")),
                            )
                        )
                }
            }

            test("response field가 descriptor와 다르면, 문서 생성에 실패한다") {
                shouldThrow<SnippetException> {
                    mockMvc
                        .perform(createUserRequest())
                        .andDo(
                            document(
                                "create-user-response-mismatch",
                                responseFields(
                                    fieldWithPath("id").description("생성된 사용자 식별자"),
                                    fieldWithPath("name").description("사용자 이름"),
                                ),
                            )
                        )
                }
            }
        }
    })

private fun createUserRequest(): MockHttpServletRequestBuilder =
    post("/tenants/{tenantId}/users", "tenant-1")
        .queryParam("dryRun", "false")
        .header("X-Request-Id", "request-123")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"name":"Alice","role":"ADMIN"}""")

@RestController
private class CreateUserController {

    @PostMapping("/tenants/{tenantId}/users")
    fun createUser(
        @PathVariable tenantId: String,
        @RequestParam dryRun: Boolean,
        @RequestHeader("X-Request-Id") requestId: String,
        @RequestBody request: String,
    ): ResponseEntity<String> {
        check(!dryRun)
        check(request.contains("Alice"))

        return ResponseEntity.status(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.LOCATION, "/tenants/$tenantId/users/user-123")
            .header("X-Request-Id", requestId)
            .body("""{"id":"user-123","name":"Alice","role":"ADMIN"}""")
    }
}
