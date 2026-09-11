package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import tools.jackson.databind.ObjectMapper

class HttpContextCompilerTest :
    FunSpec({
      val metadataResolver = ValueMetadataResolver(ObjectMapper())
      val headerCompiler = HeaderCompiler(metadataResolver)
      val bodyCompiler = BodyCompiler(FieldDescriptorCompiler(metadataResolver))

      context("Header context 컴파일") {
        test("Header가 없으면, 빈 context를 생성합니다") {
          val compiled = headerCompiler.compile(Headers())

          compiled.headers.shouldBeEmpty()
        }

        test("Header를 컴파일하면, ignored 요소를 제외하고 선언 순서를 유지합니다") {
          val compiled =
              headerCompiler.compile(
                  Headers(
                      listOf(
                          Header("X-Request-Id", "요청 ID", sampleOf("request-1")),
                          Header(
                              key = "X-Debug",
                              description = "디버깅 정보",
                              sample = sampleOf(true),
                              ignored = true,
                          ),
                          Header("X-Retry-Count", "재시도 횟수", sampleOf(3)),
                      )
                  )
              )

          compiled.headers.map { it.descriptor.name } shouldContainExactly
              listOf("X-Request-Id", "X-Retry-Count")
        }

        test("모든 Header가 ignored이면, 빈 context를 생성합니다") {
          val compiled =
              headerCompiler.compile(
                  Headers(
                      listOf(
                          Header(
                              key = "X-Debug",
                              description = "디버깅 정보",
                              sample = sampleOf(true),
                              ignored = true,
                          )
                      )
                  )
              )

          compiled.headers.shouldBeEmpty()
        }
      }

      context("Body context 컴파일") {
        test("Field가 없으면, 빈 context를 생성합니다") {
          val compiled = bodyCompiler.compile(Body())

          compiled.fields.shouldBeEmpty()
        }

        test("Field를 컴파일하면, ignored 상태를 포함해 선언 순서를 유지합니다") {
          val compiled =
              bodyCompiler.compile(
                  Body(
                      listOf(
                          Field("name", "사용자 이름", sampleOf("Alice")),
                          Field(
                              key = "legacyCode",
                              description = "이전 시스템 코드",
                              sample = sampleOf("legacy"),
                              ignored = true,
                          ),
                          Field("age", "사용자 나이", sampleOf(20)),
                      )
                  )
              )

          compiled.fields.map { it.path } shouldContainExactly listOf("name", "legacyCode", "age")
        }
      }
    })
