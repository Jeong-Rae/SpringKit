package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.springframework.restdocs.payload.FieldDescriptor

/** Spring REST Docs 본문 snippet에 사용할 field descriptor 목록입니다. */
data class CompiledBody(
    val fields: List<FieldDescriptor>,
)

/** Core [Body]의 field를 선언된 순서로 컴파일합니다. */
class BodyCompiler(
    private val fieldCompiler: FieldDescriptorCompiler,
) {
  fun compile(body: Body): CompiledBody =
      CompiledBody(
          fields = body.fields.map(fieldCompiler::compile),
      )
}
