package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.snippet.Snippet

/** Spring REST Docs 실행에 사용할 문서 식별자와 snippet 목록 */
data class CompiledDocumentation(
    val identifier: String,
    val snippets: List<Snippet>,
)

/** Core [Documentation]의 모든 HTTP context와 resource snippet을 조합하는 최상위 컴파일러 */
class SpringRestDocsCompiler(
    private val requestLineCompiler: RequestLineCompiler,
    private val headerCompiler: HeaderCompiler,
    private val bodyCompiler: BodyCompiler,
) {
  private val standardSnippetComposer = StandardSnippetComposer()
  private val resourceSnippetComposer = ResourceSnippetComposer()

  fun compile(documentation: Documentation): CompiledDocumentation {
    val requestLine = requestLineCompiler.compile(documentation.requestLine)
    val requestHeaders = headerCompiler.compile(documentation.requestHeaders)
    val requestBody = bodyCompiler.compile(documentation.requestBody)
    val responseHeaders = headerCompiler.compile(documentation.responseHeaders)
    val responseBody = bodyCompiler.compile(documentation.responseBody)

    val standardSnippets =
        standardSnippetComposer.compose(
            requestLine = requestLine,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
        )
    val resourceSnippet =
        resourceSnippetComposer.compose(
            documentation = documentation,
            requestLine = requestLine,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
        )

    return CompiledDocumentation(
        identifier = documentation.name,
        snippets = standardSnippets + resourceSnippet,
    )
  }
}
