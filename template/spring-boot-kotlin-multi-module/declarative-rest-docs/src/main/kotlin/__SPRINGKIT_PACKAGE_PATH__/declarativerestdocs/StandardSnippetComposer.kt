package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders as requestHeadersSnippet
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders as responseHeadersSnippet
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.snippet.Snippet

/** 컴파일된 HTTP context를 정해진 순서의 Spring REST Docs 표준 snippet으로 조합합니다. */
class StandardSnippetComposer {
  fun compose(
      requestLine: CompiledRequestLine,
      requestHeaders: CompiledHeaders,
      requestBody: CompiledBody,
      responseHeaders: CompiledHeaders,
      responseBody: CompiledBody,
  ): List<Snippet> = buildList {
    add(requestLine.validationSnippet)

    if (requestLine.pathParameters.isNotEmpty()) {
      add(pathParameters(requestLine.pathParameters.map { it.descriptor }))
    }

    if (requestLine.queryParameters.isNotEmpty()) {
      add(queryParameters(requestLine.queryParameters.map { it.descriptor }))
    }

    if (requestHeaders.headers.isNotEmpty()) {
      add(requestHeadersSnippet(requestHeaders.headers.map { it.descriptor }))
    }

    if (requestBody.fields.isNotEmpty()) {
      add(requestFields(requestBody.fields))
    }

    if (responseHeaders.headers.isNotEmpty()) {
      add(responseHeadersSnippet(responseHeaders.headers.map { it.descriptor }))
    }

    if (responseBody.fields.isNotEmpty()) {
      add(responseFields(responseBody.fields))
    }
  }
}
