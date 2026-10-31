package com.springkit.agentguidebook.models

/**
 * 소스 코드에 적용할 프로그래밍 언어 이름입니다.
 *
 * @property value 공백이 아닌 언어 식별자
 * @throws IllegalArgumentException 값이 비어 있거나 공백뿐인 경우
 */
@JvmInline
value class Language(val value: String) {
  init {
    require(value.isNotBlank()) { "언어 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
