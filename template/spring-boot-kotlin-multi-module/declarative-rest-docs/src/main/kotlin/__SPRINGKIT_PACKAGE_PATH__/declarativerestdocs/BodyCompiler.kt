package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.request.RequestDocumentation.partWithName
import org.springframework.restdocs.request.RequestPartDescriptor

/** Spring REST Docs 본문 snippet에 사용할 field descriptor 목록 */
data class CompiledBody(
    val fields: List<FieldDescriptor>,
)

/** 요청 본문 형식에 맞춘 REST Docs 및 OpenAPI 컴파일 결과 */
internal data class CompiledRequestBody(
    val format: RequestBodyFormat,
    val fields: List<FieldDescriptor>,
    val requestParts: List<RequestPartDescriptor>,
    val resourceFields: List<FieldDescriptor>,
    val binaryParts: List<String>,
)

/** Core [Body] field의 선언 순서를 보존하는 컴파일 */
class BodyCompiler
private constructor(
    private val fieldCompiler: FieldDescriptorCompiler,
    private val requestBodyFormatResolver: RequestBodyFormatResolver,
) {
  constructor(
      fieldCompiler: FieldDescriptorCompiler
  ) : this(fieldCompiler, RequestBodyFormatResolver())

  fun compile(body: Body): CompiledBody =
      CompiledBody(
          fields = body.fields.map(fieldCompiler::compile),
      )

  internal fun compileRequest(
      body: Body,
      requestHeaders: Headers,
  ): CompiledRequestBody {
    val format = requestBodyFormatResolver.resolve(body, requestHeaders)
    return when (format.kind) {
      RequestBodyFormat.Kind.NONE ->
          CompiledRequestBody(format, emptyList(), emptyList(), emptyList(), emptyList())
      RequestBodyFormat.Kind.JSON -> {
        val fields = body.fields.map(fieldCompiler::compile)
        CompiledRequestBody(format, fields, emptyList(), fields, emptyList())
      }
      RequestBodyFormat.Kind.RAW ->
          CompiledRequestBody(
              format = format,
              fields = emptyList(),
              requestParts = emptyList(),
              resourceFields = emptyList(),
              binaryParts = emptyList(),
          )
      RequestBodyFormat.Kind.MULTIPART -> {
        val parts = body.fields.map(::compileRequestPart)
        val resourceFields = body.fields.filterNot(Field::ignored).map(::compileResourcePart)
        val binaryParts =
            body.fields.filter { it.sample.value is ByteArray && !it.ignored }.map(Field::key)
        CompiledRequestBody(format, emptyList(), parts, resourceFields, binaryParts)
      }
    }
  }

  private fun compileRequestPart(field: Field): RequestPartDescriptor =
      partWithName(field.key).description(field.description).apply {
        if (field.optional) {
          optional()
        }
        if (field.ignored) {
          ignored()
        }
      }

  private fun compileResourcePart(field: Field): FieldDescriptor =
      if (field.sample.value is ByteArray) {
        fieldWithPath(field.key).description(field.description).type(JsonFieldType.STRING).apply {
          if (field.optional) {
            optional()
          }
        }
      } else {
        fieldCompiler.compile(field)
      }
}
