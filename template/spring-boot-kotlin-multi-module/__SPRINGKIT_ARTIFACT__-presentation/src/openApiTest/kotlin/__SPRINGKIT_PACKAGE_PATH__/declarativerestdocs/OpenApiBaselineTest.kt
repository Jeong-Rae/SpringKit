package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml

class OpenApiBaselineTest :
    FunSpec({
        context("create-user OpenAPI 기준선") {
            test("OpenAPI 문서를 생성하면, create-user 계약을 포함한다") {
                val root =
                    Files.newBufferedReader(Path.of("build/api-spec/openapi3.yaml")).use {
                        Yaml().load<Map<String, Any>>(it)
                    }
                val operation = root.map("paths").map("/tenants/{tenantId}/users").map("post")

                root["openapi"] shouldBe "3.0.1"
                operation["operationId"] shouldBe "create-user"
                operation["summary"] shouldBe "사용자 생성"
                operation["description"] shouldBe "테넌트에 새로운 사용자를 생성합니다."
                operation["tags"] shouldBe listOf("users")

                assertParameter(operation, "tenantId", "path", "string")
                assertParameter(operation, "dryRun", "query", "boolean")
                assertParameter(operation, "X-Request-Id", "header", "string")

                val requestSchema =
                    operation
                        .map("requestBody")
                        .map("content")
                        .map("application/json")
                        .map("schema")
                assertSchemaProperties(root, requestSchema, setOf("name", "role"))

                val createdResponse = operation.map("responses").map("201")
                createdResponse.map("headers").map("Location").map("schema")["type"] shouldBe
                    "string"
                val responseSchema =
                    createdResponse.map("content").map("application/json").map("schema")
                assertSchemaProperties(root, responseSchema, setOf("id", "name", "role"))
            }
        }
    })

private fun assertParameter(
    operation: Map<String, Any>,
    name: String,
    location: String,
    type: String,
) {
    val parameter =
        operation.listOfMaps("parameters").single { it["name"] == name && it["in"] == location }

    parameter["required"] shouldBe true
    parameter.map("schema")["type"] shouldBe type
}

private fun assertSchemaProperties(
    root: Map<String, Any>,
    schemaReference: Map<String, Any>,
    expectedProperties: Set<String>,
) {
    val schemaName = schemaReference.string("$" + "ref").substringAfterLast("/")
    val schema = root.map("components").map("schemas").map(schemaName)

    schema["type"] shouldBe "object"
    schema.map("properties").keys shouldBe expectedProperties
    schema.list("required") shouldContainAll expectedProperties
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
