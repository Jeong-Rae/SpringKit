package com.springkit.agentguidebook.domain

@JvmInline
value class GuidelineEvent(val value: String) {
  init {
    require(value.isNotBlank()) { "이벤트 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
