package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ResourceDocumentation.resource
import com.epages.restdocs.apispec.ResourceSnippetParameters
import java.nio.file.Files
import org.springframework.restdocs.RestDocumentationContext
import org.springframework.restdocs.operation.Operation
import org.springframework.restdocs.snippet.Snippet
import tools.jackson.databind.ObjectMapper

/** 컴파일된 HTTP context를 API Spec resource snippet으로 조합 */
internal class ResourceSnippetComposer {
  fun compose(
      documentation: Documentation,
      requestLine: CompiledRequestLine,
      requestHeaders: CompiledHeaders,
      requestBody: CompiledRequestBody,
      responseHeaders: CompiledHeaders,
      responseBody: CompiledBody,
  ): Snippet {
    val resourceSnippet =
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
    if (
        requestBody.format.kind !in
            setOf(RequestBodyFormat.Kind.RAW, RequestBodyFormat.Kind.MULTIPART)
    ) {
      return resourceSnippet
    }

    val rawField = requestBody.format.rawField
    return RequestBodyMetadataSnippet(
        delegate = resourceSnippet,
        metadata =
            RequestBodyMetadata(
                operationId = documentation.name,
                kind = requestBody.format.kind.name,
                contentType = requestBody.format.effectiveContentType,
                description = rawField?.description,
                optional = rawField?.optional ?: false,
                binaryParts = requestBody.binaryParts,
                resourceFields =
                    requestBody.resourceFields.map { descriptor ->
                      val field = requestBody.format.fields.single { it.key == descriptor.path }
                      RequestBodyResourceField(
                          path = descriptor.path,
                          description = descriptor.description.toString(),
                          type = partSchemaType(field.sample, descriptor.type.toString()),
                          contentType = partContentType(field.sample.value),
                          optional = descriptor.isOptional,
                          ignored = descriptor.isIgnored,
                          attributes = descriptor.attributes,
                      )
                    },
            ),
    )
  }

  internal fun composeParameters(
      documentation: Documentation,
      requestLine: CompiledRequestLine,
      requestHeaders: CompiledHeaders,
      requestBody: CompiledRequestBody,
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
          .requestFields(
              if (requestBody.format.kind == RequestBodyFormat.Kind.JSON) {
                requestBody.resourceFields
              } else {
                emptyList()
              }
          )
          .responseHeaders(responseHeaders.headers.map { it.resourceDescriptor })
          .responseFields(responseBody.fields)
          .build()
}

/** API spec generator가 표현하지 못하는 raw/multipart 상세 정보를 snippets에 남깁니다. */
internal data class RequestBodyMetadata(
    val operationId: String,
    val kind: String,
    val contentType: String?,
    val description: String?,
    val optional: Boolean = false,
    val binaryParts: List<String>,
    val resourceFields: List<RequestBodyResourceField>,
)

internal data class RequestBodyResourceField(
    val path: String,
    val description: String,
    val type: String,
    val contentType: String,
    val optional: Boolean,
    val ignored: Boolean,
    val attributes: Map<String, Any>,
)

private fun partContentType(value: Any): String =
    when (value) {
      is ByteArray -> "application/octet-stream"
      is String -> "text/plain;charset=UTF-8"
      else -> "application/json"
    }

private fun partSchemaType(sample: Sample, descriptorType: String): String =
    when (sample.type.classifier) {
      Byte::class,
      Short::class,
      Int::class,
      Long::class,
      java.math.BigInteger::class -> "INTEGER"
      else -> descriptorType.uppercase()
    }

private class RequestBodyMetadataSnippet(
    private val delegate: Snippet,
    private val metadata: RequestBodyMetadata,
) : Snippet {
  private val objectMapper = ObjectMapper()

  override fun document(operation: Operation) {
    delegate.document(operation)
    val context =
        operation.attributes[RestDocumentationContext::class.java.name] as? RestDocumentationContext
            ?: error("REST Docs context가 없습니다.")
    val directory = context.outputDirectory.toPath().resolve(operation.name)
    Files.createDirectories(directory)
    if (metadata.kind == RequestBodyFormat.Kind.MULTIPART.name) {
      val resourcePath = directory.resolve("resource.json")
      @Suppress("UNCHECKED_CAST")
      val resource =
          objectMapper.readValue(resourcePath.toFile(), MutableMap::class.java)
              as MutableMap<String, Any?>
      @Suppress("UNCHECKED_CAST")
      val request =
          resource["request"] as? MutableMap<String, Any?> ?: error("resource.json request가 없습니다.")
      request["requestFields"] = metadata.resourceFields.map { it.toMap() }
      request.remove("example")
      objectMapper.writeValue(resourcePath.toFile(), resource)
    }
    if (metadata.kind == RequestBodyFormat.Kind.RAW.name) {
      val description = requireNotNull(metadata.description)
      Files.writeString(
          directory.resolve("request-body.adoc"),
          "요청 본문\n\n$description\n\nContent-Type: ${metadata.contentType}\n",
      )
    }
    objectMapper.writeValue(directory.resolve(FILE_NAME).toFile(), metadata)
  }

  private fun RequestBodyResourceField.toMap(): Map<String, Any> =
      linkedMapOf(
          "attributes" to attributes,
          "description" to description,
          "ignored" to ignored,
          "path" to path,
          "type" to type,
          "optional" to optional,
      )

  companion object {
    const val FILE_NAME = "request-body-metadata.json"
  }
}
