package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.core.MatchCondition
import com.springkit.agentguidebook.core.Scope
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** YAML 문서를 검증해 검색 도메인의 지침으로 변환합니다. */
internal class GuidelineYamlParser {

  /** 지정한 지침 YAML 파일을 안전하게 읽고 구조를 검증합니다. */
  fun read(filepath: Path): Guideline {
    val document = readDocument(filepath)
    val parsed =
        try {
          yamlParser().load<Any?>(document)
        } catch (exception: YAMLException) {
          throw GuidelineLoadException("지침 YAML을 해석할 수 없습니다: $filepath", exception)
        }

    return toGuideline(parsed, filepath)
  }

  private fun readDocument(filepath: Path): String =
      try {
        val bytes = Files.readAllBytes(filepath)
        val decoder =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

        decoder.decode(ByteBuffer.wrap(bytes)).toString()
      } catch (exception: IOException) {
        throw GuidelineLoadException("지침 YAML 파일을 읽을 수 없습니다: $filepath", exception)
      } catch (exception: SecurityException) {
        throw GuidelineLoadException("지침 YAML 파일을 읽을 수 없습니다: $filepath", exception)
      }

  private fun yamlParser(): Yaml {
    val loaderOptions = LoaderOptions()
    loaderOptions.setAllowDuplicateKeys(false)
    return Yaml(SafeConstructor(loaderOptions))
  }

  private fun toGuideline(value: Any?, filepath: Path): Guideline {
    return try {
      val document = mapping(value, "문서")
      validateFields(document, DOCUMENT_FIELDS, "문서")

      val id = GuidelineId(string(requiredValue(document, "id", "문서"), "id"))
      val scope = parseScope(requiredValue(document, "scope", "문서"))
      val events =
          strings(requiredValue(document, "when", "문서"), "when").map(::GuidelineEvent).toSet()
      val match = MatchCondition(parsePatterns(requiredValue(document, "match", "문서")))
      val content = string(requiredValue(document, "content", "문서"), "content")

      Guideline(id, scope, events, match, content)
    } catch (exception: GuidelineLoadException) {
      throw exception
    } catch (exception: IllegalArgumentException) {
      throw GuidelineLoadException("지침 필드가 올바르지 않습니다: $filepath", exception)
    }
  }

  private fun parseScope(value: Any?): Scope {
    val fields = mapping(value, "scope")
    validateFields(fields, SCOPE_FIELDS, "scope")

    val repositories = strings(requiredValue(fields, "repositories", "scope"), "scope.repositories")
    val languages = strings(requiredValue(fields, "languages", "scope"), "scope.languages")

    return Scope(repositories, languages)
  }

  private fun parsePatterns(value: Any?): List<String> {
    val fields = mapping(value, "match")
    validateFields(fields, MATCH_FIELDS, "match")
    val candidates = sequence(requiredValue(fields, "any", "match"), "match.any")

    return candidates.mapIndexed { index, candidate ->
      val candidateFields = mapping(candidate, "match.any[$index]")
      validateFields(candidateFields, REGEX_FIELDS, "match.any[$index]")

      val regexFields =
          mapping(requiredValue(candidateFields, "regex", "match.any[$index]"), "regex")
      validateFields(regexFields, PATTERN_FIELDS, "match.any[$index].regex")

      string(
          requiredValue(regexFields, "pattern", "match.any[$index].regex"),
          "match.any[$index].regex.pattern",
      )
    }
  }

  private fun mapping(value: Any?, location: String): Map<*, *> =
      value as? Map<*, *> ?: throw GuidelineLoadException("지침 필드는 객체여야 합니다: $location")

  private fun sequence(value: Any?, location: String): List<*> =
      value as? List<*> ?: throw GuidelineLoadException("지침 필드는 목록이어야 합니다: $location")

  private fun strings(value: Any?, location: String): List<String> =
      sequence(value, location).mapIndexed { index, item -> string(item, "$location[$index]") }

  private fun string(value: Any?, location: String): String =
      value as? String ?: throw GuidelineLoadException("지침 필드는 문자열이어야 합니다: $location")

  private fun requiredValue(fields: Map<*, *>, key: String, location: String): Any? {
    if (!fields.containsKey(key)) {
      throw GuidelineLoadException("필수 지침 필드가 없습니다: $location.$key")
    }

    return fields[key]
  }

  private fun validateFields(fields: Map<*, *>, allowed: Set<String>, location: String) {
    val hasInvalidField = fields.keys.any { key -> key !is String || key !in allowed }

    if (hasInvalidField) {
      val invalidField = fields.keys.first { key -> key !is String || key !in allowed }
      throw GuidelineLoadException("알 수 없는 지침 필드입니다: $location.$invalidField")
    }
  }

  private companion object {
    val DOCUMENT_FIELDS = setOf("id", "scope", "when", "match", "content")
    val SCOPE_FIELDS = setOf("repositories", "languages")
    val MATCH_FIELDS = setOf("any")
    val REGEX_FIELDS = setOf("regex")
    val PATTERN_FIELDS = setOf("pattern")
  }
}
