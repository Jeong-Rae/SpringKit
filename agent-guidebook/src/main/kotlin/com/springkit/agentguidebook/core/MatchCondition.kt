package com.springkit.agentguidebook.core

import java.util.ArrayList
import java.util.Collections
import java.util.regex.PatternSyntaxException

/**
 * 최대 하나의 정규식으로 지침과 소스 코드의 관련성을 판단합니다.
 *
 * 정규식이 없으면 어떤 소스 내용도 일치하지 않습니다.
 *
 * @param patterns 검증 후 사용할 정규식 목록
 * @throws IllegalArgumentException 패턴이 여러 개이거나, 패턴 문자열이 공백뿐이거나 정규식 문법이 잘못된 경우
 */
class MatchCondition(patterns: Collection<String>) {
  val patterns: List<String> = Collections.unmodifiableList(ArrayList(patterns))

  private val regex: Regex?

  init {
    require(this.patterns.size <= 1) { "정규식은 최대 1개까지만 지정할 수 있습니다." }
    require(this.patterns.none(String::isBlank)) {
      "정규식은 비어 있거나 공백으로만 구성될 수 없습니다."
    }

    regex = this.patterns.singleOrNull()?.let(::compilePattern)
  }

  /**
   * 소스 코드 안에서 정규식 부분 일치를 찾습니다.
   *
   * @param source 검사할 소스 코드
   * @return 정규식이 설정되어 있고 내용 일부와 일치하면 `true`
   */
  fun matches(source: String): Boolean = regex?.containsMatchIn(source) ?: false

  override fun equals(other: Any?): Boolean =
      this === other || (other is MatchCondition && patterns == other.patterns)

  override fun hashCode(): Int = patterns.hashCode()

  private fun compilePattern(pattern: String): Regex =
      try {
        Regex(pattern)
      } catch (exception: PatternSyntaxException) {
        throw IllegalArgumentException("정규식 패턴이 올바르지 않습니다.", exception)
      }
}
