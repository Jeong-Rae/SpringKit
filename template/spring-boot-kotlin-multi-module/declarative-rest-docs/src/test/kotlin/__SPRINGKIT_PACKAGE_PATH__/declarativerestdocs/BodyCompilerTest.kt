package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import tools.jackson.databind.ObjectMapper

class BodyCompilerTest :
    FunSpec({
      val compiler =
          BodyCompiler(
              FieldDescriptorCompiler(
                  ValueMetadataResolver(ObjectMapper()),
              )
          )

      context("Body 컴파일") {
        test("여러 Field를 컴파일하면 descriptor의 선언 순서와 상태가 보존됩니다") {
          val body =
              Body(
                  listOf(
                      Field("name", "사용자 이름", sampleOf("Alice")),
                      Field(
                          key = "profile.nickname",
                          description = "사용자 별명",
                          sample = sampleOf("ally"),
                          optional = true,
                      ),
                      Field(
                          key = "legacyCode",
                          description = "이전 시스템 코드",
                          sample = sampleOf("legacy"),
                          ignored = true,
                      ),
                  )
              )

          val compiled = compiler.compile(body)

          compiled.fields.map { it.path } shouldBe listOf("name", "profile.nickname", "legacyCode")
          compiled.fields.map { it.isOptional } shouldBe listOf(false, true, false)
          compiled.fields.map { it.isIgnored } shouldBe listOf(false, false, true)
        }

        test("빈 Body를 컴파일하면 descriptor 목록이 비어 있습니다") {
          compiler.compile(Body()).fields.shouldBeEmpty()
        }
      }
    })
