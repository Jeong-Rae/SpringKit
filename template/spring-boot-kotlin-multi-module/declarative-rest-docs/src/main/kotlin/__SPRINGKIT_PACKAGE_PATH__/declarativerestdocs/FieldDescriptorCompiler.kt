package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath

/** Core [Field]를 Spring REST Docs [FieldDescriptor]로 변환합니다. */
class FieldDescriptorCompiler(
    private val metadataResolver: ValueMetadataResolver,
) {
  fun compile(field: Field): FieldDescriptor {
    val metadata = metadataResolver.resolve(field.sample)

    return fieldWithPath(field.key)
        .description(field.description)
        .type(metadata.fieldType)
        .attributes(*metadata.attributes.toTypedArray())
        .apply {
          if (field.optional) {
            optional()
          }

          if (field.ignored) {
            ignored()
          }
        }
  }
}
