package com.springkit.agentguidebook.core

import java.util.ArrayList
import java.util.Collections
import java.util.regex.PatternSyntaxException

/** 정규식 하나 이하로 소스 코드와 지침의 관련성을 판단합니다. */
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

  /** 조건이 비어 있으면 일치하지 않으며, 소스 코드 안에서 부분 일치를 찾습니다. */
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
