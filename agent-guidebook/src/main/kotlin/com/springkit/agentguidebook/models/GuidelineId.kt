package com.springkit.agentguidebook.models

/**
 * 지침을 식별하는 공백이 아닌 값입니다.
 *
 * @property value 입력한 지침 식별자
 * @throws IllegalArgumentException 값이 비어 있거나 공백뿐인 경우
 */
@JvmInline
value class GuidelineId(val value: String) {
  init {
    require(value.isNotBlank()) { "지침 ID는 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
