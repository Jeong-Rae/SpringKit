package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import java.util.ArrayList
import java.util.Collections

/** 저장소와 언어 조건을 함께 평가합니다. */
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

  /** 각 목록에서는 하나 이상의 조건을 만족해야 하며, 저장소와 언어 조건은 모두 만족해야 합니다. */
  fun matches(context: ApplicabilityContext): Boolean {
    val matchesRepository = repositoryPatterns.any { pattern ->
      pattern.matches(repositoryName(context))
    }
    val matchesLanguage = languages.any { it == "*" || it == context.language.value }

    return matchesRepository && matchesLanguage
  }

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is Scope && repositories == other.repositories && languages == other.languages)

  override fun hashCode(): Int = 31 * repositories.hashCode() + languages.hashCode()

  private fun repositoryName(context: ApplicabilityContext): String =
      "${context.repository.owner}/${context.repository.repository}"

  private fun repositoryPattern(pattern: String): Regex =
      Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)
}
