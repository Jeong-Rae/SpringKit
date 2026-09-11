package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.payload.FieldDescriptor

/** Spring REST Docs 본문 snippet에 사용할 field descriptor 목록 */
data class CompiledBody(
    val fields: List<FieldDescriptor>,
)

/** Core [Body] field의 선언 순서를 보존하는 컴파일 */
class BodyCompiler(
    private val fieldCompiler: FieldDescriptorCompiler,
) {
  fun compile(body: Body): CompiledBody =
      CompiledBody(
          fields = body.fields.map(fieldCompiler::compile),
      )
}
