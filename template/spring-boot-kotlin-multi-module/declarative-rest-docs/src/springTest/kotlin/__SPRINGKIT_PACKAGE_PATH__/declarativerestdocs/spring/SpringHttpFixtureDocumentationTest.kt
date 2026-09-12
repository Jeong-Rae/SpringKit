package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.spring

import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyDsl
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.Documentation
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.SpringRestDocsCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.ValueMetadataResolver
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.BodyCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.FieldDescriptorCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.HeaderCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.ParameterCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestLineCompiler
import __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.documentationDefinition
import jakarta.servlet.http.Cookie
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.head
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.multipart
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.options
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [SpringFixtureApplication::class])
class SpringHttpFixtureDocumentationTest {
  @Autowired private lateinit var applicationContext: WebApplicationContext
  @Autowired private lateinit var objectMapper: ObjectMapper

  @Test
  fun getMembers() {
    documentOperation(
        documentation = getMembersDocumentation(),
        request =
            get("/api/members")
                .queryParam("page", "0")
                .queryParam("size", "20")
                .queryParam("status", "ACTIVE")
                .queryParam("tag", "spring", "java")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Request-Id", "req-001")
                .accept(MediaType.APPLICATION_JSON),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun getMember() {
    documentOperation(
        documentation = getMemberDocumentation(),
        request =
            get("/api/members/{memberId}", 1)
                .cookie(Cookie("SESSION", "session-token"))
                .header(HttpHeaders.COOKIE, "SESSION=session-token")
                .accept(MediaType.APPLICATION_JSON),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun createMember() {
    documentOperation(
        documentation = createMemberDocumentation(),
        request =
            post("/api/members")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "create-member-001")
                .content(
                    """
                    {
                      "name": "Jane",
                      "email": "jane@example.com",
                      "active": true,
                      "profile": {
                        "age": 30,
                        "tags": ["java", "spring"]
                      }
                    }
                    """.trimIndent()
                ),
        expectedStatus = status().isCreated,
    )
  }

  @Test
  fun replaceMember() {
    documentOperation(
        documentation = replaceMemberDocumentation(),
        request =
            put("/api/members/{memberId}", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.IF_MATCH, "\"member-1-v1\"")
                .content(
                    """
                    {
                      "name": "Jane Doe",
                      "email": "jane.doe@example.com",
                      "active": false,
                      "profile": {
                        "age": 31,
                        "tags": []
                      }
                    }
                    """.trimIndent()
                ),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun patchMember() {
    documentOperation(
        documentation = patchMemberDocumentation(),
        request =
            patch("/api/members/{memberId}", 1)
                .contentType(MediaType.parseMediaType("application/merge-patch+json"))
                .accept(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Jane Smith",
                      "nickname": null
                    }
                    """.trimIndent()
                ),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun deleteMember() {
    documentOperation(
        documentation = deleteMemberDocumentation(),
        request = delete("/api/members/{memberId}", 1),
        expectedStatus = status().isNoContent,
    )
  }

  @Test
  fun headMember() {
    documentOperation(
        documentation = headMemberDocumentation(),
        request = head("/api/members/{memberId}", 1).accept(MediaType.APPLICATION_JSON),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun optionsMember() {
    documentOperation(
        documentation = optionsMemberDocumentation(),
        request = options("/api/members/{memberId}", 1),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun createSession() {
    documentOperation(
        documentation = createSessionDocumentation(),
        request =
            post("/api/sessions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .param("username", "jane")
                .param("password", "secret")
                .param("rememberMe", "true"),
        expectedStatus = status().isOk,
    )
  }

  @Test
  fun uploadAvatar() {
    val metadata =
        MockMultipartFile(
            "metadata",
            "",
            MediaType.APPLICATION_JSON_VALUE,
            """
            {
              "crop": {
                "x": 10,
                "y": 20
              },
              "public": true
            }
            """.trimIndent().toByteArray(),
        )
    val file =
        MockMultipartFile(
            "file",
            "avatar.png",
            MediaType.IMAGE_PNG_VALUE,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        )

    documentOperation(
        documentation = uploadAvatarDocumentation(),
        request =
            multipart("/api/members/{memberId}/avatar", 1)
                .file(metadata)
                .file(file)
                .accept(MediaType.APPLICATION_JSON),
        expectedStatus = status().isOk,
    )
  }

  private fun documentOperation(
      documentation: Documentation,
      request: RequestBuilder,
      expectedStatus: ResultMatcher,
  ) {
    val restDocumentation = ManualRestDocumentation(snippetsRoot().toString())
    restDocumentation.beforeTest(javaClass, documentation.name)

    try {
      val mockMvc =
          webAppContextSetup(applicationContext)
              .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
              .build()
      val compiled = compiler().compile(documentation)

      mockMvc
          .perform(request)
          .andExpect(expectedStatus)
          .andDo(document(compiled.identifier, *compiled.snippets.toTypedArray()))
    } finally {
      restDocumentation.afterTest()
    }
  }

  private fun compiler(): SpringRestDocsCompiler {
    val metadataResolver = ValueMetadataResolver(objectMapper)
    return SpringRestDocsCompiler(
        requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
        headerCompiler = HeaderCompiler(metadataResolver),
        bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
    )
  }

  private fun snippetsRoot(): Path =
      Path.of(
          requireNotNull(System.getProperty("springkit.spring-fixture.snippets")) {
            "springkit.spring-fixture.snippets 시스템 프로퍼티가 필요합니다."
          }
      )
}

private fun getMembersDocumentation(): Documentation =
    documentationDefinition("get-members") {
      summary = "회원 목록 조회"
      description = "회원 목록을 조회합니다."
      tags("members")
      requestLine("get", "/api/members") {
        queryParameter("page", "페이지 번호", sample = 0)
        queryParameter("size", "페이지 크기", sample = 20)
        queryParameter("status", "회원 상태", sample = "ACTIVE")
        queryParameter("tag", "회원 태그", sample = "spring")
      }
      requestHeader {
        header(HttpHeaders.AUTHORIZATION, "Bearer 인증 토큰", sample = "Bearer test-token")
        header("X-Request-Id", "요청 추적 식별자", sample = "req-001")
      }
      responseBody { memberPageFields() }
    }

private fun getMemberDocumentation(): Documentation =
    documentationDefinition("get-member") {
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

private fun createMemberDocumentation(): Documentation =
    documentationDefinition("create-member") {
      summary = "회원 생성"
      description = "JSON 요청으로 회원을 생성합니다."
      tags("members")
      requestLine("post", "/api/members")
      requestHeader {
        header("Idempotency-Key", "멱등성 식별자", sample = "create-member-001")
      }
      requestBody { memberWriteFields("Jane", "jane@example.com", true, 30, listOf("java", "spring")) }
      responseHeader {
        header(HttpHeaders.LOCATION, "생성된 회원 URI", sample = "/api/members/1")
      }
      responseBody { memberFields() }
    }

private fun replaceMemberDocumentation(): Documentation =
    documentationDefinition("replace-member") {
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

private fun patchMemberDocumentation(): Documentation =
    documentationDefinition("patch-member") {
      summary = "회원 부분 수정"
      description = "JSON Merge Patch로 회원 일부 정보를 수정합니다."
      tags("members")
      requestLine("patch", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
      requestBody {
        field("name", "변경할 회원 이름", sample = "Jane Smith")
        ignoredField("nickname", "명시적으로 제거할 별명", sample = "nickname")
      }
      responseBody {
        field("id", "회원 식별자", sample = 1L)
        field("name", "회원 이름", sample = "Jane Smith")
        ignoredField("nickname", "회원 별명", sample = "nickname")
      }
    }

private fun deleteMemberDocumentation(): Documentation =
    documentationDefinition("delete-member") {
      summary = "회원 삭제"
      description = "회원을 삭제하고 204 응답을 반환합니다."
      tags("members")
      requestLine("delete", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
    }

private fun headMemberDocumentation(): Documentation =
    documentationDefinition("head-member") {
      summary = "회원 HEAD 조회"
      description = "회원 리소스의 응답 헤더만 조회합니다."
      tags("members")
      requestLine("head", "/api/members/{memberId}") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
    }

private fun optionsMemberDocumentation(): Documentation =
    documentationDefinition("options-member") {
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

private fun createSessionDocumentation(): Documentation =
    documentationDefinition("create-session") {
      summary = "세션 생성"
      description = "form-urlencoded 요청으로 세션을 생성합니다."
      tags("sessions")
      requestLine("post", "/api/sessions")
      requestHeader {
        header(
            HttpHeaders.CONTENT_TYPE,
            "form-urlencoded 요청 Content-Type",
            sample = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        )
      }
      responseHeader {
        header(HttpHeaders.SET_COOKIE, "생성된 세션 쿠키", sample = "SESSION=session-token")
      }
      responseBody {
        field("memberId", "회원 식별자", sample = 1L)
        field("rememberMe", "로그인 유지 여부", sample = true)
      }
    }

private fun uploadAvatarDocumentation(): Documentation =
    documentationDefinition("upload-avatar") {
      summary = "회원 아바타 업로드"
      description = "multipart 요청으로 JSON metadata와 이미지 파일을 업로드합니다."
      tags("members")
      requestLine("post", "/api/members/{memberId}/avatar") {
        pathVariable("memberId", "회원 식별자", sample = 1L)
      }
      requestHeader {
        header(
            HttpHeaders.CONTENT_TYPE,
            "multipart 요청 Content-Type",
            sample = MediaType.MULTIPART_FORM_DATA_VALUE,
        )
      }
      responseBody {
        field("memberId", "회원 식별자", sample = 1L)
        field("filename", "업로드 파일 이름", sample = "avatar.png")
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
