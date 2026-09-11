package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.ParameterDescriptorWithType
import com.epages.restdocs.apispec.ResourceDocumentation.parameterWithName as resourceParameterWithName
import org.springframework.restdocs.request.ParameterDescriptor
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName

/** Spring REST Docs와 ResourceSnippet에 함께 사용할 파라미터 descriptor입니다. */
data class CompiledParameter(
    val descriptor: ParameterDescriptor,
    val resourceDescriptor: ParameterDescriptorWithType,
)

/** Core path variable과 query parameter를 표준 및 typed descriptor로 변환합니다. */
class ParameterCompiler(
    private val metadataResolver: ValueMetadataResolver,
) {
  fun compile(parameter: PathVariable): CompiledParameter =
      compile(
          parameter = parameter,
          optional = false,
          ignored = false,
      )

  fun compile(parameter: QueryParameter): CompiledParameter =
      compile(
          parameter = parameter,
          optional = parameter.optional,
          ignored = parameter.ignored,
      )

  private fun compile(
      parameter: ValueElement,
      optional: Boolean,
      ignored: Boolean,
  ): CompiledParameter {
    val metadata = metadataResolver.resolve(parameter.sample)
    val simpleType =
        requireNotNull(metadata.simpleType) {
          "${parameter.key} 파라미터의 sample 타입을 typed descriptor로 변환할 수 없습니다."
        }

    val descriptor =
        parameterWithName(parameter.key).description(parameter.description).apply {
          if (optional) {
            optional()
          }

          if (ignored) {
            ignored()
          }
        }
    val resourceDescriptor =
        resourceParameterWithName(parameter.key)
            .description(parameter.description)
            .type(simpleType)
            .apply {
              if (optional) {
                optional()
              }

              if (ignored) {
                ignored()
              }
            }

    return CompiledParameter(
        descriptor = descriptor,
        resourceDescriptor = resourceDescriptor,
    )
  }
}
