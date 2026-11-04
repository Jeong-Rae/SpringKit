package com.springkit.agentguidebook.domain

@JvmInline
value class GuidelineId(val value: String) {
  init {
    require(value.isNotBlank()) { "지침 ID는 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
