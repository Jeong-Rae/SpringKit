package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.http.HttpMethod
import org.springframework.restdocs.generate.RestDocumentationGenerator
import org.springframework.restdocs.operation.Operation
import org.springframework.restdocs.snippet.Snippet
import org.springframework.restdocs.snippet.SnippetException

/** Spring REST Docs 요청 검증과 파라미터 문서에 사용할 request line 컴파일 결과입니다. */
data class CompiledRequestLine(
    val validationSnippet: Snippet,
    val pathParameters: List<CompiledParameter>,
    val queryParameters: List<CompiledParameter>,
)

/** Core [RequestLine]을 요청 검증 snippet과 파라미터 descriptor로 변환합니다. */
class RequestLineCompiler(
    private val parameterCompiler: ParameterCompiler,
) {
  fun compile(requestLine: RequestLine): CompiledRequestLine =
      CompiledRequestLine(
          validationSnippet =
              RequestLineValidationSnippet(
                  expectedMethod = requestLine.method,
                  expectedUri = requestLine.uri,
              ),
          pathParameters = requestLine.pathVariables.map(parameterCompiler::compile),
          queryParameters = requestLine.queryParameters.map(parameterCompiler::compile),
      )
}

/** 실제 HTTP 요청의 method와 URI template이 문서의 request line과 일치하는지 검증합니다. */
class RequestLineValidationSnippet(
    private val expectedMethod: HttpMethod,
    private val expectedUri: String,
) : Snippet {
  override fun document(operation: Operation) {
    val actualMethod = operation.request.method
    if (actualMethod != expectedMethod) {
      throw SnippetException(
          "HTTP method가 일치하지 않습니다. expected=$expectedMethod, actual=$actualMethod"
      )
    }

    val actualUri =
        operation.attributes[RestDocumentationGenerator.ATTRIBUTE_NAME_URL_TEMPLATE] as? String
    if (actualUri != expectedUri) {
      throw SnippetException("URI template이 일치하지 않습니다. expected=$expectedUri, actual=$actualUri")
    }
  }
}
