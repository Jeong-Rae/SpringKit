package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.HeaderDescriptorWithType
import com.epages.restdocs.apispec.ResourceDocumentation.headerWithName as resourceHeaderWithName
import org.springframework.restdocs.headers.HeaderDescriptor
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName

/** Spring REST Docs와 ResourceSnippet에 함께 사용할 header descriptor */
data class CompiledHeader(
    val descriptor: HeaderDescriptor,
    val resourceDescriptor: HeaderDescriptorWithType,
)

/** Spring REST Docs와 ResourceSnippet에 함께 사용할 header context 컴파일 결과 */
data class CompiledHeaders(
    val headers: List<CompiledHeader>,
)

/** Core [Header]의 표준 및 typed descriptor 변환과 ignored header 제외 */
class HeaderCompiler(
    private val metadataResolver: ValueMetadataResolver,
) {
  fun compile(headers: Headers): CompiledHeaders =
      CompiledHeaders(
          headers = headers.headers.mapNotNull(::compile),
      )

  fun compile(header: Header): CompiledHeader? {
    if (header.ignored) {
      return null
    }

    val metadata = metadataResolver.resolve(header.sample)
    val simpleType =
        requireNotNull(metadata.simpleType) {
          "${header.key} header의 sample 타입을 typed descriptor로 변환할 수 없습니다."
        }
    val descriptor =
        headerWithName(header.key).description(header.description).apply {
          if (header.optional) {
            optional()
          }
        }
    val resourceDescriptor =
        resourceHeaderWithName(header.key).description(header.description).type(simpleType).apply {
          if (header.optional) {
            optional()
          }
        }

    return CompiledHeader(
        descriptor = descriptor,
        resourceDescriptor = resourceDescriptor,
    )
  }
}
