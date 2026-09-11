package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.fasterxml.jackson.annotation.JsonValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.restdocs.payload.JsonFieldType
import tools.jackson.databind.ObjectMapper

class FieldDescriptorCompilerTest :
    FunSpec({
      val compiler = FieldDescriptorCompiler(ValueMetadataResolver(ObjectMapper()))

      context("Field descriptor 컴파일") {
        test("일반 Field를 컴파일하면, Field의 정보와 기본 상태가 FieldDescriptor에 적용됩니다") {
          val descriptor = compiler.compile(Field("name", "사용자 이름", sampleOf("Alice")))

          descriptor.path shouldBe "name"
          descriptor.description shouldBe "사용자 이름"
          descriptor.type shouldBe JsonFieldType.STRING
          descriptor.attributes.shouldBeEmpty()
          descriptor.isOptional shouldBe false
          descriptor.isIgnored shouldBe false
        }

        test("중첩 경로가 있는 optional Field를 컴파일하면, 경로와 optional 상태가 보존됩니다") {
          val descriptor =
              compiler.compile(
                  Field(
                      key = "profile.nickname",
                      description = "사용자 별명",
                      sample = sampleOf("ally"),
                      optional = true,
                  )
              )

          descriptor.path shouldBe "profile.nickname"
          descriptor.isOptional shouldBe true
          descriptor.isIgnored shouldBe false
        }

        test("ignored가 true인 Field를 컴파일하면, ignored 상태가 적용됩니다") {
          val descriptor =
              compiler.compile(
                  Field(
                      key = "legacyCode",
                      description = "이전 시스템 코드",
                      sample = sampleOf("legacy"),
                      ignored = true,
                  )
              )

          descriptor.isOptional shouldBe false
          descriptor.isIgnored shouldBe true
        }

        test("enum Field를 컴파일하면, enum 메타데이터가 적용됩니다") {
          val descriptor =
              compiler.compile(Field("role", "사용자 역할", sampleOf(FieldCompilerSerializedRole.ADMIN)))

          descriptor.type shouldBe "enum"
          descriptor.attributes shouldBe mapOf("enumValues" to listOf("user", "admin"))
        }

        test("enum 컬렉션 Field를 컴파일하면, 배열 타입과 원소 메타데이터가 적용됩니다") {
          val descriptor =
              compiler.compile(
                  Field(
                      "roles",
                      "사용자 역할 목록",
                      sampleOf(
                          listOf(
                              FieldCompilerSerializedRole.USER,
                              FieldCompilerSerializedRole.ADMIN,
                          )
                      ),
                  )
              )

          descriptor.type shouldBe JsonFieldType.ARRAY
          descriptor.attributes shouldBe
              mapOf(
                  "itemsType" to "ENUM",
                  "enumValues" to listOf("user", "admin"),
              )
        }
      }

      context("Field metadata 해석 횟수") {
        test("Field 하나를 컴파일하면, 메타데이터가 한 번만 해석됩니다") {
          CountingRole.serializationCount = 0
          val countingCompiler = FieldDescriptorCompiler(ValueMetadataResolver(ObjectMapper()))

          countingCompiler.compile(Field("role", "사용자 역할", sampleOf(CountingRole.ADMIN)))

          CountingRole.serializationCount shouldBe CountingRole.entries.size
        }
      }
    })

private enum class FieldCompilerSerializedRole(@get:JsonValue val serializedValue: String) {
  USER("user"),
  ADMIN("admin"),
}

private enum class CountingRole(private val serializedValue: String) {
  USER("user"),
  ADMIN("admin");

  @JsonValue
  fun value(): String {
    serializationCount += 1
    return serializedValue
  }

  companion object {
    var serializationCount: Int = 0
  }
}
