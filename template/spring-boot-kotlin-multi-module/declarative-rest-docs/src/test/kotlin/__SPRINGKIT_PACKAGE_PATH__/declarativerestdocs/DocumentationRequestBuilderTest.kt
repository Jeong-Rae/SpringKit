package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockMultipartHttpServletRequest
import org.springframework.mock.web.MockServletContext
import tools.jackson.databind.ObjectMapper

class DocumentationRequestBuilderTest :
    FunSpec({
      val objectMapper = ObjectMapper()
      val requestBuilder = DocumentationRequestBuilder(objectMapper)

      context("Documentation sample 기반 요청 생성") {
        test("모든 요청 context를 선언하면, sample로 HTTP 요청을 생성합니다") {
          val documentation =
              documentationDefinition("create-user") {
                summary = "사용자 생성"
                description = "사용자를 생성합니다."
                requestLine("post", "/users/{userId}") {
                  pathVariable("userId", "사용자 식별자", sample = "user-123")
                  queryParameter("dryRun", "검증 여부", sample = false)
                  queryParameter("tag", "사용자 태그", sample = listOf("spring", "java"))
                  queryParameter("rating", "평점", sample = arrayOf(1, 2))
                }
                requestHeader {
                  header("X-Request-Id", "요청 식별자", sample = "request-123")
                }
                requestBody {
                  field("name", "사용자 이름", sample = "Alice")
                  field("profile.nickname", "사용자 별명", sample = "ally")
                  field("members[].id", "구성원 식별자", sample = "member-1")
                }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())
          val body = objectMapper.readTree(request.contentAsByteArray)

          request.method shouldBe "POST"
          request.requestURI shouldBe "/users/user-123"
          request.getParameter("dryRun") shouldBe "false"
          requireNotNull(request.getParameterValues("tag")).toList() shouldBe
              listOf("spring", "java")
          requireNotNull(request.getParameterValues("rating")).toList() shouldBe listOf("1", "2")
          request.getHeader("X-Request-Id") shouldBe "request-123"
          request.contentType shouldBe "application/json"
          body.at("/name").stringValue() shouldBe "Alice"
          body.at("/profile/nickname").stringValue() shouldBe "ally"
          body.at("/members/0/id").stringValue() shouldBe "member-1"
        }

        test("반복 query sample의 null 원소를 거부합니다") {
          val documentation =
              documentationDefinition("invalid-query") {
                summary = "잘못된 질의 값"
                description = "null 원소가 있는 질의 값을 거부합니다."
                requestLine("get", "/users") {
                  queryParameter("tag", "사용자 태그", sample = listOf("spring", null))
                }
              }

          shouldThrow<IllegalArgumentException> { requestBuilder.build(documentation) }
        }

        test("Content-Type header를 실제 JSON 요청에 반영합니다") {
          val documentation =
              documentationDefinition("patch-user") {
                summary = "사용자 부분 수정"
                description = "사용자 일부 정보를 수정합니다."
                requestLine("patch", "/users/1")
                requestHeader {
                  header(
                      "Content-Type",
                      "JSON Merge Patch Content-Type",
                      sample = "application/merge-patch+json",
                  )
                }
                requestBody {
                  field("name", "사용자 이름", sample = "Alice")
                }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())
          val body = objectMapper.readTree(request.contentAsByteArray)

          request.contentType shouldBe "application/merge-patch+json"
          body.at("/name").stringValue() shouldBe "Alice"
        }

        test("raw ByteArray는 기본 octet-stream으로 그대로 전송합니다") {
          val bytes = byteArrayOf(0, 1, 2, 0xff.toByte())
          val documentation =
              documentationDefinition("upload-raw") {
                summary = "raw 업로드"
                description = "바이트를 업로드합니다."
                requestLine("put", "/uploads/raw")
                requestBody { field("payload", "본문 전체", sample = bytes) }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())

          request.contentType shouldBe "application/octet-stream"
          request.contentAsByteArray.contentEquals(bytes) shouldBe true
        }

        test("application/json을 명시한 raw body도 JSON 직렬화 없이 그대로 전송합니다") {
          val bytes = "{\"raw\":true}".toByteArray()
          val documentation =
              documentationDefinition("upload-raw-json") {
                summary = "JSON bytes 업로드"
                description = "명시한 JSON Content-Type으로 원본 바이트를 전송합니다."
                requestLine("post", "/uploads/raw-json")
                requestHeader {
                  header("cOnTeNt-TyPe", "본문 Content-Type", sample = "application/json")
                }
                requestBody { field("ignored-key", "본문 전체", sample = bytes) }
              }

          val request = requestBuilder.build(documentation).buildRequest(MockServletContext())

          request.contentType shouldBe "application/json"
          request.contentAsByteArray.contentEquals(bytes) shouldBe true
        }

        test("multipart는 선언한 method와 Content-Type을 유지하고 part encoding을 구분합니다") {
          val fileBytes = byteArrayOf(0, 1, 0xff.toByte())
          val documentation =
              documentationDefinition("upload-multipart") {
                summary = "multipart 업로드"
                description = "파일과 메타데이터를 업로드합니다."
                requestLine("put", "/uploads/{id}") { pathVariable("id", "식별자", sample = "one") }
                requestHeader {
                  header(
                      "cOnTeNt-TyPe",
                      "multipart Content-Type",
                      sample = "multipart/mixed; boundary=custom",
                  )
                }
                requestBody {
                  field("file", "파일 part", sample = fileBytes)
                  field("caption", "문자열 part", sample = "cover photo")
                  field("priority", "정수 part", sample = 3)
                  field("metadata", "객체 part", sample = mapOf("enabled" to true))
                }
              }

          val request =
              requestBuilder.build(documentation).buildRequest(MockServletContext())
                  as MockMultipartHttpServletRequest
          val parts = request.fileMap

          request.method shouldBe "PUT"
          request.getHeader("Content-Type") shouldBe "multipart/mixed; boundary=custom"
          request.contentType shouldBe "multipart/mixed; boundary=custom"
          parts.keys shouldBe setOf("file", "caption", "priority", "metadata")
          parts.getValue("file").originalFilename shouldBe "file"
          parts.getValue("file").contentType shouldBe "application/octet-stream"
          parts.getValue("file").bytes.contentEquals(fileBytes) shouldBe true
          parts.getValue("caption").contentType shouldBe "text/plain;charset=UTF-8"
          parts.getValue("caption").bytes.decodeToString() shouldBe "cover photo"
          parts.getValue("priority").contentType shouldBe "application/json"
          objectMapper.readTree(parts.getValue("priority").bytes).intValue() shouldBe 3
          objectMapper
              .readTree(parts.getValue("metadata").bytes)
              .at("/enabled")
              .booleanValue() shouldBe true
        }

        test("multipart가 아닌 body에서 ByteArray와 구조화 필드를 섞으면 오류를 냅니다") {
          val documentation =
              documentationDefinition("mixed-raw-json") {
                summary = "raw와 JSON 필드 혼합"
                description = "모호한 본문 선언을 거부합니다."
                requestLine("post", "/uploads/mixed")
                requestBody {
                  field("payload", "raw body", sample = byteArrayOf(1))
                  field("name", "이름", sample = "Alice")
                }
              }

          shouldThrow<IllegalArgumentException> { requestBuilder.build(documentation) }
        }

        test("multipart가 아닌 body에서 여러 ByteArray field를 선언하면 오류를 냅니다") {
          val documentation =
              documentationDefinition("multiple-raw") {
                summary = "여러 raw 필드"
                description = "모호한 본문 선언을 거부합니다."
                requestLine("post", "/uploads/multiple")
                requestBody {
                  field("first", "첫 번째", sample = byteArrayOf(1))
                  field("second", "두 번째", sample = byteArrayOf(2))
                }
              }

          shouldThrow<IllegalArgumentException> { requestBuilder.build(documentation) }
        }
      }
    })
