package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/** API 계약 선언만으로 실제 요청과 REST Docs 산출물을 생성하는 공통 테스트 기반입니다. */
@SpringBootTest
@ExtendWith(RestDocumentationExtension::class)
abstract class DeclarativeRestDocsTest {
  @Autowired private lateinit var applicationContext: WebApplicationContext
  @Autowired private lateinit var objectMapper: ObjectMapper
  private lateinit var mockMvc: MockMvc

  @BeforeEach
  protected fun configureRestDocumentation(
      restDocumentation: RestDocumentationContextProvider,
  ) {
    mockMvc =
        webAppContextSetup(applicationContext)
            .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
            .build()
  }

  /** DSL을 Core 모델로 변환하고 실제 HTTP 요청과 REST Docs 문서 생성을 완료합니다. */
  protected fun documentation(
      name: String,
      block: DocumentationDsl.() -> Unit,
  ) {
    val definition = documentationDefinition(name, block)
    val request = DocumentationRequestBuilder(objectMapper).build(definition)
    val result = mockMvc.perform(request).andExpect(status().is2xxSuccessful)
    val compiled = compiler().compile(definition)

    result.andDo(document(compiled.identifier, *compiled.snippets.toTypedArray()))
  }

  private fun compiler(): SpringRestDocsCompiler {
    val metadataResolver = ValueMetadataResolver(objectMapper)
    return SpringRestDocsCompiler(
        requestLineCompiler = RequestLineCompiler(ParameterCompiler(metadataResolver)),
        headerCompiler = HeaderCompiler(metadataResolver),
        bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver)),
    )
  }
}
