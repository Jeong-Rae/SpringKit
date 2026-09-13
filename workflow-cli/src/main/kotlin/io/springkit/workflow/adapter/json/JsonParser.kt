package io.springkit.workflow.adapter.json

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The location of a malformed JSON token. Offsets are zero-based UTF-16 offsets. */
class JsonParseException(
    message: kotlin.String,
    val offset: Int,
    val line: Int,
    val column: Int,
    cause: Throwable? = null,
) : IllegalArgumentException("$message at line $line, column $column (offset $offset)", cause)

/** A strict JSON parser with no reflection or third-party dependencies. */
object JsonParser {
  fun parse(input: kotlin.String): JsonValue = Parser(input).parseDocument()

  fun parse(input: ByteArray): JsonValue {
    val inputBuffer = ByteBuffer.wrap(input)
    val charBuffer = CharBuffer.allocate(input.size.coerceAtLeast(1))
    val decoder =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    val result = decoder.decode(inputBuffer, charBuffer, true)
    if (result.isError) {
      throw JsonParseException(
          "Invalid UTF-8 input",
          inputBuffer.position(),
          1,
          inputBuffer.position() + 1,
      )
    }
    val flushResult = decoder.flush(charBuffer)
    if (flushResult.isError) {
      throw JsonParseException(
          "Invalid UTF-8 input",
          inputBuffer.position(),
          1,
          inputBuffer.position() + 1,
      )
    }
    charBuffer.flip()
    return parse(charBuffer.toString())
  }

  private class Parser(private val source: kotlin.String) {
    private var index = 0

    fun parseDocument(): JsonValue {
      skipWhitespace()
      if (index == source.length) fail("Expected a JSON value")
      val result = parseValue()
      skipWhitespace()
      if (index != source.length) fail("Unexpected trailing input")
      return result
    }

    private fun parseValue(): JsonValue {
      if (index == source.length) fail("Expected a JSON value")
      return when (source[index]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> JsonValue.String(parseString())
        't' -> {
          expectLiteral("true")
          JsonValue.Boolean(true)
        }
        'f' -> {
          expectLiteral("false")
          JsonValue.Boolean(false)
        }
        'n' -> {
          expectLiteral("null")
          JsonValue.Null
        }
        '-',
        in '0'..'9' -> JsonValue.Number(parseNumber())
        else -> fail("Unexpected character '${source[index]}'")
      }
    }

    private fun parseObject(): JsonValue.Object {
      consume('{')
      skipWhitespace()
      val fields = LinkedHashMap<kotlin.String, JsonValue>()
      if (consumeIf('}')) return JsonValue.Object(fields)

      while (true) {
        if (index == source.length || source[index] != '"') {
          fail("Expected a quoted object member name")
        }
        val keyOffset = index
        val key = parseString()
        if (fields.containsKey(key)) {
          fail("Duplicate object member '$key'", keyOffset)
        }
        skipWhitespace()
        consume(':')
        skipWhitespace()
        fields[key] = parseValue()
        skipWhitespace()
        when {
          consumeIf('}') -> return JsonValue.Object(fields)
          consumeIf(',') -> {
            skipWhitespace()
            if (index < source.length && source[index] == '}') {
              fail("Trailing comma is not allowed")
            }
          }
          else -> fail("Expected ',' or '}' after an object member")
        }
      }
    }

    private fun parseArray(): JsonValue.Array {
      consume('[')
      skipWhitespace()
      val values = ArrayList<JsonValue>()
      if (consumeIf(']')) return JsonValue.Array(values)

      while (true) {
        values += parseValue()
        skipWhitespace()
        when {
          consumeIf(']') -> return JsonValue.Array(values)
          consumeIf(',') -> {
            skipWhitespace()
            if (index < source.length && source[index] == ']') {
              fail("Trailing comma is not allowed")
            }
          }
          else -> fail("Expected ',' or ']' after an array value")
        }
      }
    }

    private fun parseString(): kotlin.String {
      consume('"')
      val result = StringBuilder()
      while (index < source.length) {
        when (val character = source[index++]) {
          '"' -> return result.toString()
          '\\' -> parseEscape(result)
          in '\u0000'..'\u001f' -> fail("Unescaped control character in a string", index - 1)
          else -> {
            if (character.isSurrogate()) {
              fail("Unpaired UTF-16 surrogate in a string", index - 1)
            }
            result.append(character)
          }
        }
      }
      fail("Unterminated string")
    }

    private fun parseEscape(result: StringBuilder) {
      if (index == source.length) fail("Unterminated escape sequence")
      when (val escaped = source[index++]) {
        '"' -> result.append('"')
        '\\' -> result.append('\\')
        '/' -> result.append('/')
        'b' -> result.append('\b')
        'f' -> result.append('\u000c')
        'n' -> result.append('\n')
        'r' -> result.append('\r')
        't' -> result.append('\t')
        'u' -> parseUnicodeEscape(result)
        else -> fail("Invalid escape sequence '\\$escaped'", index - 1)
      }
    }

    private fun parseUnicodeEscape(result: StringBuilder) {
      val escapeOffset = index - 2
      val first = parseHexQuad(escapeOffset)
      when {
        first in 0xD800..0xDBFF -> {
          if (index + 5 >= source.length || source[index] != '\\' || source[index + 1] != 'u') {
            fail("A high surrogate must be followed by a low surrogate", escapeOffset)
          }
          index += 2
          val second = parseHexQuad(index - 2)
          if (second !in 0xDC00..0xDFFF) {
            fail("A high surrogate must be followed by a low surrogate", index - 6)
          }
          result.append(first.toChar())
          result.append(second.toChar())
        }
        first in 0xDC00..0xDFFF ->
            fail("A low surrogate cannot appear without a high surrogate", escapeOffset)
        else -> result.append(first.toChar())
      }
    }

    private fun parseHexQuad(escapeOffset: Int): Int {
      if (index + 4 > source.length) fail("Incomplete unicode escape", escapeOffset)
      var result = 0
      repeat(4) {
        val digit =
            source[index++].digitToIntOrNull(16)
                ?: fail("Invalid hexadecimal digit in unicode escape", index - 1)
        result = (result shl 4) or digit
      }
      return result
    }

    private fun parseNumber(): kotlin.String {
      val start = index
      if (consumeIf('-')) {
        if (index == source.length) fail("Expected a digit after '-'")
      }

      when {
        consumeIf('0') -> {
          if (index < source.length && source[index] in '0'..'9') {
            fail("Leading zeroes are not allowed", index)
          }
        }
        index < source.length && source[index] in '1'..'9' -> {
          while (index < source.length && source[index] in '0'..'9') index++
        }
        else -> fail("Expected a digit in a number")
      }

      if (consumeIf('.')) {
        val fractionStart = index
        while (index < source.length && source[index] in '0'..'9') index++
        if (fractionStart == index) fail("Expected a digit after '.'")
      }

      if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
        index++
        if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
        val exponentStart = index
        while (index < source.length && source[index] in '0'..'9') index++
        if (exponentStart == index) fail("Expected a digit in the exponent")
      }
      return source.substring(start, index)
    }

    private fun expectLiteral(literal: kotlin.String) {
      if (!source.regionMatches(index, literal, 0, literal.length)) {
        fail("Expected '$literal'")
      }
      index += literal.length
    }

    private fun skipWhitespace() {
      while (index < source.length) {
        when (source[index]) {
          ' ',
          '\t',
          '\n',
          '\r' -> index++
          else -> return
        }
      }
    }

    private fun consume(expected: Char) {
      if (!consumeIf(expected)) fail("Expected '$expected'")
    }

    private fun consumeIf(expected: Char): kotlin.Boolean {
      if (index < source.length && source[index] == expected) {
        index++
        return true
      }
      return false
    }

    private fun fail(message: kotlin.String, at: Int = index): Nothing {
      val safeOffset = at.coerceIn(0, source.length)
      var line = 1
      var lineStart = 0
      source.forEachIndexed { position, character ->
        if (position >= safeOffset) return@forEachIndexed
        if (character == '\n') {
          line++
          lineStart = position + 1
        }
      }
      throw JsonParseException(message, safeOffset, line, safeOffset - lineStart + 1)
    }
  }
}
