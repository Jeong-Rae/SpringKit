package io.springkit.workflow.adapter.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonParserTest {
  @Test
  fun parsesEveryJsonValueKind() {
    val value =
        JsonParser.parse(
            """
            {"name":"workflow","enabled":true,"count":12.50e+2,"items":[null,false,"ok"]}
            """
                .trimIndent(),
        )

    assertEquals(
        JsonValue.Object.of(
            "count" to JsonValue.Number("12.50e+2"),
            "enabled" to JsonValue.Boolean(true),
            "items" to
                JsonValue.Array.of(
                    JsonValue.Null,
                    JsonValue.Boolean(false),
                    JsonValue.String("ok"),
                ),
            "name" to JsonValue.String("workflow"),
        ),
        value,
    )
  }

  @Test
  fun parsesUtf8BytesAndRejectsMalformedUtf8() {
    assertEquals(JsonValue.String("한글"), JsonParser.parse("\"한글\"".toByteArray(Charsets.UTF_8)))

    val exception =
        assertFailsWith<JsonParseException> {
          JsonParser.parse(byteArrayOf(0x22, 0xC3.toByte(), 0x28, 0x22))
        }
    assertEquals(1, exception.offset)
  }

  @Test
  fun parsesEscapesAndSurrogatePairs() {
    val value = JsonParser.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\uD834\\uDD1E\"")
    assertEquals("\"\\/\b\u000c\n\r\t\uD834\uDD1E", (value as JsonValue.String).value)
  }

  @Test
  fun rendersDeterministicallyWithSortedKeysAndEscapedControls() {
    val value =
        JsonValue.Object.of(
            "z" to JsonValue.String("line\n\u0001"),
            "a" to JsonValue.Array.of(JsonValue.Number("1e+2"), JsonValue.Null),
        )

    assertEquals("{\"a\":[1e+2,null],\"z\":\"line\\n\\u0001\"}", JsonRenderer.render(value))
    assertEquals(JsonRenderer.render(value), value.renderJson())
  }

  @Test
  fun rejectsDuplicateKeysAndTrailingInput() {
    val duplicate =
        assertFailsWith<JsonParseException> {
          JsonParser.parse("{\"a\":1,\"a\":2}")
        }
    assertEquals(7, duplicate.offset)

    val trailing =
        assertFailsWith<JsonParseException> {
          JsonParser.parse("true false")
        }
    assertEquals(5, trailing.offset)
  }

  @Test
  fun rejectsInvalidNumbersAndTrailingCommas() {
    listOf("01", "-", "1.", "1e", "1E+", "-01", "[1,]", "{\"a\":1,}").forEach { input ->
      assertFailsWith<JsonParseException>(input) { JsonParser.parse(input) }
    }

    assertFailsWith<IllegalArgumentException> { JsonValue.Number("NaN") }
  }

  @Test
  fun reportsLineAndColumn() {
    val exception =
        assertFailsWith<JsonParseException> {
          JsonParser.parse("{\n  \"a\": 1,\n  \"b\": ]}")
        }

    assertEquals(3, exception.line)
    assertEquals(8, exception.column)
  }

  @Test
  fun rejectsUnpairedSurrogates() {
    assertFailsWith<JsonParseException> { JsonParser.parse("\"\\uD834\"") }
    assertFailsWith<JsonParseException> { JsonParser.parse("\"\\uDD1E\"") }
    assertFailsWith<IllegalArgumentException> { JsonValue.String("\uD834") }
  }
}
