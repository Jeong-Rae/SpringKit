package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceDocumentation.resource
import com.epages.restdocs.apispec.ResourceSnippet
import com.epages.restdocs.apispec.ResourceSnippetParameters

/** 컴파일된 HTTP context를 API Spec resource snippet으로 조합 */
class ResourceSnippetComposer {
  fun compose(
      documentation: Documentation,
      requestLine: CompiledRequestLine,
      requestHeaders: CompiledHeaders,
      requestBody: CompiledBody,
      responseHeaders: CompiledHeaders,
      responseBody: CompiledBody,
  ): ResourceSnippet =
      resource(
          composeParameters(
              documentation = documentation,
              requestLine = requestLine,
              requestHeaders = requestHeaders,
              requestBody = requestBody,
              responseHeaders = responseHeaders,
              responseBody = responseBody,
          )
      )

  internal fun composeParameters(
      documentation: Documentation,
      requestLine: CompiledRequestLine,
      requestHeaders: CompiledHeaders,
      requestBody: CompiledBody,
      responseHeaders: CompiledHeaders,
      responseBody: CompiledBody,
  ): ResourceSnippetParameters =
      ResourceSnippetParameters.builder()
          .summary(documentation.summary)
          .description(documentation.description)
          .tags(*documentation.tags.toTypedArray())
          .pathParameters(requestLine.pathParameters.map { it.resourceDescriptor })
          .queryParameters(requestLine.queryParameters.map { it.resourceDescriptor })
          .requestHeaders(requestHeaders.headers.map { it.resourceDescriptor })
          .requestFields(requestBody.fields)
          .responseHeaders(responseHeaders.headers.map { it.resourceDescriptor })
          .responseFields(responseBody.fields)
          .build()
}
