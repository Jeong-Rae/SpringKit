package com.springkit.agentguidebook.domain

import java.util.ArrayList
import java.util.Collections

/** 저장소 패턴의 `*`는 임의 문자열에 일치합니다. */
class Scope(
    repositories: Collection<String>,
    languages: Collection<String>,
) {
  val repositories: List<String> = Collections.unmodifiableList(ArrayList(repositories))
  val languages: List<String> = Collections.unmodifiableList(ArrayList(languages))

  init {
    require(this.repositories.all(String::isNotBlank)) {
      "저장소 조건은 비어 있거나 공백으로만 구성될 수 없습니다."
    }
    require(this.languages.all(String::isNotBlank)) {
      "언어 조건은 비어 있거나 공백으로만 구성될 수 없습니다."
    }
  }

  private val repositoryPatterns = this.repositories.map(::repositoryPattern)

  fun matches(context: ApplicabilityContext): Boolean {
    val qualifiedName = context.repository.qualifiedName()
    val matchesRepository = repositoryPatterns.any { pattern ->
      pattern.matches(qualifiedName)
    }
    val matchesLanguage = languages.any { language ->
      val appliesToEveryLanguage = language == "*"
      val matchesRequestedLanguage = language == context.language.value

      appliesToEveryLanguage || matchesRequestedLanguage
    }

    return matchesRepository && matchesLanguage
  }

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is Scope && repositories == other.repositories && languages == other.languages)

  override fun hashCode(): Int = 31 * repositories.hashCode() + languages.hashCode()

  private fun repositoryPattern(pattern: String): Regex =
      Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)
}
