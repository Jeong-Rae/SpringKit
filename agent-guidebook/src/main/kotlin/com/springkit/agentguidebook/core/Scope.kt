package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import java.util.ArrayList
import java.util.Collections

/**
 * 저장소와 언어 조건을 함께 평가하는 지침 적용 범위입니다.
 *
 * 각 목록은 하나라도 일치하면 조건을 만족하고, 두 목록의 조건은 모두 만족해야 합니다.
 *
 * @param repositories owner/repository에 적용할 전체 일치 패턴 모음
 * @param languages 적용할 언어 이름 모음
 * @throws IllegalArgumentException 조건에 빈 문자열 또는 공백 문자열이 포함된 경우
 */
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

  /**
   * 저장소 패턴과 언어 조건이 모두 일치하는지 확인합니다.
   *
   * @param context 적용 여부를 평가할 저장소와 언어 정보
   * @return 두 조건 목록이 모두 일치하면 `true`
   */
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
