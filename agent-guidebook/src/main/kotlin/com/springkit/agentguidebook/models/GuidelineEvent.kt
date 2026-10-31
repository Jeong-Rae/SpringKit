package com.springkit.agentguidebook.models

/**
 * 지침을 적용할 작업 이벤트의 이름입니다.
 *
 * @property value 공백이 아닌 이벤트 식별자
 * @throws IllegalArgumentException 값이 비어 있거나 공백뿐인 경우
 */
@JvmInline
value class GuidelineEvent(val value: String) {
  init {
    require(value.isNotBlank()) { "이벤트 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
