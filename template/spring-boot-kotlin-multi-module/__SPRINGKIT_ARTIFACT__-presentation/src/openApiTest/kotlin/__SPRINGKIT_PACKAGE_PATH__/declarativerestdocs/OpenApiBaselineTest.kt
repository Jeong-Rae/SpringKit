package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenApiBaselineTest {

    @Test
    fun openapi3ContainsCreateUserSemantics() {
        val root =
            Files.newBufferedReader(Path.of("build/api-spec/openapi3.yaml")).use {
                Yaml().load<Map<String, Any>>(it)
            }
        val operation =
            root.map("paths")
                .map("/tenants/{tenantId}/users")
                .map("post")

        assertEquals("3.0.1", root["openapi"])
        assertEquals("create-user", operation["operationId"])
        assertEquals("사용자 생성", operation["summary"])
        assertEquals("테넌트에 새로운 사용자를 생성합니다.", operation["description"])
        assertEquals(listOf("users"), operation["tags"])

        assertParameter(operation, "tenantId", "path", "string")
        assertParameter(operation, "dryRun", "query", "boolean")
        assertParameter(operation, "X-Request-Id", "header", "string")

        val requestSchema =
            operation.map("requestBody")
                .map("content")
                .map("application/json")
                .map("schema")
        assertSchemaProperties(root, requestSchema, setOf("name", "role"))

        val createdResponse = operation.map("responses").map("201")
        assertEquals(
            "string",
            createdResponse.map("headers").map("Location").map("schema")["type"],
        )
        val responseSchema =
            createdResponse.map("content")
                .map("application/json")
                .map("schema")
        assertSchemaProperties(root, responseSchema, setOf("id", "name", "role"))
    }

    private fun assertParameter(
        operation: Map<String, Any>,
        name: String,
        location: String,
        type: String,
    ) {
        val parameter =
            operation.listOfMaps("parameters")
                .single { it["name"] == name && it["in"] == location }

        assertEquals(true, parameter["required"])
        assertEquals(type, parameter.map("schema")["type"])
    }

    private fun assertSchemaProperties(
        root: Map<String, Any>,
        schemaReference: Map<String, Any>,
        expectedProperties: Set<String>,
    ) {
        val schemaName = schemaReference.string("$" + "ref").substringAfterLast("/")
        val schema = root.map("components").map("schemas").map(schemaName)

        assertEquals("object", schema["type"])
        assertEquals(expectedProperties, schema.map("properties").keys)
        assertTrue(schema.list("required").containsAll(expectedProperties))
    }
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
