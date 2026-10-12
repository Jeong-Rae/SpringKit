package com.springkit.agentguidebook.models

/** 프로그래밍 언어 이름을 표현합니다. */
@JvmInline
value class Language(val value: String) {
  init {
    require(value.isNotBlank()) { "언어 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
