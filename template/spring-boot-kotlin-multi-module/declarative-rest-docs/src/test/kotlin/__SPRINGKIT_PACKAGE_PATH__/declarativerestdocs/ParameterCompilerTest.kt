package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.SimpleType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import tools.jackson.databind.ObjectMapper

class ParameterCompilerTest :
    FunSpec({
      val compiler = ParameterCompiler(ValueMetadataResolver(ObjectMapper()))

      context("Path variable 컴파일") {
        test("PathVariable을 컴파일하면, 양쪽 descriptor에 정보와 required 상태가 적용됩니다") {
          val compiled = compiler.compile(PathVariable("userId", "사용자 ID", sampleOf(1L)))

          compiled.descriptor.name shouldBe "userId"
          compiled.descriptor.description shouldBe "사용자 ID"
          compiled.descriptor.isOptional shouldBe false
          compiled.descriptor.isIgnored shouldBe false
          compiled.resourceDescriptor.name shouldBe "userId"
          compiled.resourceDescriptor.description shouldBe "사용자 ID"
          compiled.resourceDescriptor.type shouldBe SimpleType.INTEGER
          compiled.resourceDescriptor.optional shouldBe false
          compiled.resourceDescriptor.isIgnored shouldBe false
        }
      }

      context("Query parameter 컴파일") {
        test("optional QueryParameter를 컴파일하면, 양쪽 descriptor에 optional 상태가 적용됩니다") {
          val compiled =
              compiler.compile(
                  QueryParameter(
                      key = "detail",
                      description = "상세 조회 여부",
                      sample = sampleOf(true),
                      optional = true,
                  )
              )

          compiled.descriptor.isOptional shouldBe true
          compiled.descriptor.isIgnored shouldBe false
          compiled.resourceDescriptor.type shouldBe SimpleType.BOOLEAN
          compiled.resourceDescriptor.optional shouldBe true
          compiled.resourceDescriptor.isIgnored shouldBe false
        }

        test("ignored QueryParameter를 컴파일하면, 양쪽 descriptor에 ignored 상태가 적용됩니다") {
          val compiled =
              compiler.compile(
                  QueryParameter(
                      key = "debug",
                      description = "디버깅 옵션",
                      sample = sampleOf(false),
                      ignored = true,
                  )
              )

          compiled.descriptor.isIgnored shouldBe true
          compiled.resourceDescriptor.isIgnored shouldBe true
        }
      }

      context("Typed resource metadata") {
        listOf(
                Triple("INTEGER", sampleOf(1), SimpleType.INTEGER),
                Triple("NUMBER", sampleOf(BigDecimal("1.5")), SimpleType.NUMBER),
                Triple("BOOLEAN", sampleOf(true), SimpleType.BOOLEAN),
                Triple("STRING", sampleOf("value"), SimpleType.STRING),
            )
            .forEach { (name, sample, expectedType) ->
              test("$name sample을 컴파일하면, resource descriptor에 $expectedType 타입이 적용됩니다") {
                val compiled = compiler.compile(QueryParameter("value", "조회 값", sample))

                compiled.resourceDescriptor.type shouldBe expectedType
              }
            }
      }

      test("파라미터 하나를 컴파일하면, enum sample metadata가 한 번만 해석됩니다") {
        CountingParameterRole.serializationCount = 0

        compiler.compile(QueryParameter("role", "사용자 역할", sampleOf(CountingParameterRole.ADMIN)))

        CountingParameterRole.serializationCount shouldBe CountingParameterRole.entries.size
      }
    })

private enum class CountingParameterRole(private val serializedValue: String) {
  USER("user"),
  ADMIN("admin");

  @com.fasterxml.jackson.annotation.JsonValue
  fun value(): String {
    serializationCount += 1
    return serializedValue
  }

  companion object {
    var serializationCount: Int = 0
  }
}
