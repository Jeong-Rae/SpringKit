package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml

class OpenApiBaselineTest :
    FunSpec({
      context("create-user OpenAPI 의미 보존") {
        test("Compiler 문서의 v1 의미를 OpenAPI 문서에 유지합니다") {
          val root =
              Files.newBufferedReader(Path.of("build/api-spec/openapi3.yaml")).use {
                Yaml().load<Map<String, Any>>(it)
              }
          val paths = root.map("paths")
          val path = paths.map("/tenants/{tenantId}/users")
          val operation = path.map("post")

          root["openapi"] shouldBe "3.0.1"
          paths.keys shouldBe setOf("/tenants/{tenantId}/users")
          path.keys shouldBe setOf("post")
          operation["operationId"] shouldBe "create-user"
          operation["summary"] shouldBe "사용자 생성"
          operation["description"] shouldBe "테넌트에 새로운 사용자를 생성합니다."
          operation["tags"] shouldBe listOf("users")

          assertParameter(
              operation,
              name = "tenantId",
              location = "path",
              description = "사용자를 생성할 테넌트 식별자",
              required = true,
              type = "string",
          )
          assertParameter(
              operation,
              name = "dryRun",
              location = "query",
              description = "사용자 생성 검증만 수행할지 여부",
              required = true,
              type = "boolean",
          )
          assertParameter(
              operation,
              name = "page",
              location = "query",
              description = "결과 페이지",
              required = false,
              type = "integer",
          )
          assertParameter(
              operation,
              name = "X-Request-Id",
              location = "header",
              description = "요청 추적 식별자",
              required = true,
              type = "string",
          )

          val requestSchema =
              operation.map("requestBody").map("content").map("application/json").map("schema")
          assertUserSchema(root, requestSchema, includeId = false)

          val createdResponse = operation.map("responses").map("201")
          assertHeader(createdResponse, "Location", "생성된 사용자 URI", "string")
          assertHeader(createdResponse, "X-RateLimit", "요청 제한", "integer")

          val responseSchema = createdResponse.map("content").map("application/json").map("schema")
          assertUserSchema(root, responseSchema, includeId = true)
        }
      }
    })

private fun assertParameter(
    operation: Map<String, Any>,
    name: String,
    location: String,
    description: String,
    required: Boolean,
    type: String,
) {
  val parameter =
      operation.listOfMaps("parameters").single { it["name"] == name && it["in"] == location }

  parameter["description"] shouldBe description
  parameter["required"] shouldBe required
  parameter.map("schema")["type"] shouldBe type
}

private fun assertHeader(
    response: Map<String, Any>,
    name: String,
    description: String,
    type: String,
) {
  val header = response.map("headers").map(name)

  header["description"] shouldBe description
  header.map("schema")["type"] shouldBe type
}

private fun assertUserSchema(
    root: Map<String, Any>,
    schemaReference: Map<String, Any>,
    includeId: Boolean,
) {
  val schema = root.resolveSchema(schemaReference)
  val expectedRequired =
      setOf("name", "active", "score", "role", "aliases", "roles") +
          if (includeId) setOf("id") else emptySet()
  val expectedProperties = expectedRequired + "nickname"

  schema["type"] shouldBe "object"
  schema.list("required").toSet() shouldBe expectedRequired
  schema.map("properties").keys shouldBe expectedProperties

  if (includeId) {
    assertProperty(schema, "id", "생성된 사용자 식별자", "string")
  }
  assertProperty(schema, "name", "사용자 이름", "string")
  assertProperty(schema, "active", "활성 상태", "boolean")
  assertProperty(schema, "score", "사용자 점수", "number")

  val role = assertProperty(schema, "role", "사용자 역할", "string")
  role["enum"] shouldBe listOf("user", "admin")

  val aliases = assertProperty(schema, "aliases", "사용자 별칭", "array")
  aliases.map("items")["type"] shouldBe "string"

  val roles = assertProperty(schema, "roles", "사용자 역할 목록", "array")
  roles.map("items")["type"] shouldBe "string"
  roles.map("items")["enum"] shouldBe listOf("user", "admin")

  val nickname = assertProperty(schema, "nickname", "사용자 별명", "string")
  nickname["nullable"] shouldBe true
}

private fun assertProperty(
    schema: Map<String, Any>,
    name: String,
    description: String,
    type: String,
): Map<String, Any> {
  val property = schema.map("properties").map(name)
  property["description"] shouldBe description
  property["type"] shouldBe type
  return property
}

private fun Map<String, Any>.resolveSchema(reference: Map<String, Any>): Map<String, Any> {
  val schemaName = reference.string("$" + "ref").substringAfterLast("/")
  return map("components").map("schemas").map(schemaName)
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.map(key: String): Map<String, Any> =
    this[key] as? Map<String, Any> ?: fail("$key 항목이 객체가 아닙니다.")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.listOfMaps(key: String): List<Map<String, Any>> =
    this[key] as? List<Map<String, Any>> ?: fail("$key 항목이 객체 목록이 아닙니다.")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.list(key: String): List<String> =
    this[key] as? List<String> ?: fail("$key 항목이 문자열 목록이 아닙니다.")

private fun Map<String, Any>.string(key: String): String =
    this[key] as? String ?: fail("$key 항목이 문자열이 아닙니다.")
