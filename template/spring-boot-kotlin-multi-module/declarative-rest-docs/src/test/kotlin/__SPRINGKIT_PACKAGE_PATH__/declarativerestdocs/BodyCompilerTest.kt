package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
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
        test("여러 Field를 컴파일하면, FieldDescriptor 목록이 선언 순서와 상태를 보존합니다") {
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

        test("빈 Body를 컴파일하면, FieldDescriptor 목록이 비어 있습니다") {
          compiler.compile(Body()).fields.shouldBeEmpty()
        }

        test("multipart 요청은 field를 JSON body가 아닌 part descriptor로 컴파일합니다") {
          val body =
              Body(
                  listOf(
                      Field("file", "업로드 파일", sampleOf(byteArrayOf(1, 2))),
                      Field("caption", "이미지 설명", sampleOf("avatar")),
                      Field("priority", "표시 순서", sampleOf(2)),
                  )
              )
          val headers =
              Headers(
                  listOf(
                      Header(
                          HttpHeaders.CONTENT_TYPE,
                          "multipart Content-Type",
                          sampleOf("multipart/form-data"),
                      )
                  )
              )

          val compiled = compiler.compileRequest(body, headers)

          compiled.fields.shouldBeEmpty()
          compiled.requestParts.map { it.name } shouldBe listOf("file", "caption", "priority")
          compiled.resourceFields.map { it.path } shouldBe listOf("file", "caption", "priority")
          compiled.binaryParts shouldBe listOf("file")
        }
      }
    })
