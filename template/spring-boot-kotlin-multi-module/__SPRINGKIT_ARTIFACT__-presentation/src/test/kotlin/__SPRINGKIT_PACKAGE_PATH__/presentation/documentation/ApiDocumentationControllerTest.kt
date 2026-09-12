package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ApiDocumentationControllerTest {
  @Autowired private lateinit var mockMvc: MockMvc

  @Test
  fun `모든 프로필에서 패키징한 OpenAPI를 Scalar 문서로 제공합니다`() {
    mockMvc
        .perform(get("/api/docs"))
        .andExpect(status().isOk)
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("Scalar.createApiReference")))
        .andExpect(content().string(containsString("content, documentDownloadType: 'none'")))
        .andExpect(content().string(not(containsString("/openapi3.json"))))
  }

  @Test
  fun `Scalar JavaScript를 애플리케이션에서 제공합니다`() {
    mockMvc
        .perform(get("/api/docs/scalar.js"))
        .andExpect(status().isOk)
        .andExpect(content().contentTypeCompatibleWith("application/javascript"))
  }
}
