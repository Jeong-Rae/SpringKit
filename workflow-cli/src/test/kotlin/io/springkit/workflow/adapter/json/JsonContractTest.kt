package io.springkit.workflow.adapter.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@kotlinx.serialization.Serializable private data class KnownJsonValue(val value: String)

@OptIn(ExperimentalSerializationApi::class)
class JsonContractTest {
  @Test
  fun `kotlinx json handles JsonObject primitives and compact output`() {
    val value = buildJsonObject {
      put("name", JsonPrimitive("workflow"))
      put("enabled", true)
      put("count", 2)
    }

    assertEquals("workflow", value["name"]?.jsonPrimitive?.content)
    assertEquals("{\"name\":\"workflow\",\"enabled\":true,\"count\":2}", value.toString())
  }

  @Test
  fun `workflow Json configuration rejects unknown keys and stays compact`() {
    assertFailsWith<kotlinx.serialization.json.JsonDecodingException> {
      WorkflowJson.format.decodeFromString<KnownJsonValue>("{\"value\":\"ok\",\"extra\":true}")
    }
    assertEquals(
        "{\"value\":\"ok\"}",
        WorkflowJson.format.encodeToString(KnownJsonValue("ok")),
    )
    assertEquals(false, WorkflowJson.format.configuration.explicitNulls)
    assertEquals(true, WorkflowJson.format.configuration.encodeDefaults)
    assertEquals(false, WorkflowJson.format.configuration.ignoreUnknownKeys)
    assertEquals("type", WorkflowJson.format.configuration.classDiscriminator)
    assertEquals(false, WorkflowJson.format.configuration.prettyPrint)
  }

  @Test
  fun `library parser rejects malformed JSON`() {
    assertFailsWith<kotlinx.serialization.json.JsonDecodingException> {
      Json.parseToJsonElement("{\"value\":}")
    }
  }
}
