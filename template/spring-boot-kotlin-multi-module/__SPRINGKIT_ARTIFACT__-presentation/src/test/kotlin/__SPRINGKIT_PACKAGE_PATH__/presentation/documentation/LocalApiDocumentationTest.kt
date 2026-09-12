package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalApiDocumentationTest {
  @Autowired private lateinit var mockMvc: MockMvc

  @Test
  fun `local 프로필에서 Scalar JSON 다운로드를 제공합니다`() {
    mockMvc
        .perform(get("/api/docs"))
        .andExpect(status().isOk)
        .andExpect(content().string(containsString("documentDownloadType: 'json'")))
  }

  @Test
  fun `local 프로필에서 OpenAPI JSON을 내보냅니다`() {
    mockMvc
        .perform(get("/openapi3.json"))
        .andExpect(status().isOk)
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.openapi").value("3.0.1"))
  }
}
