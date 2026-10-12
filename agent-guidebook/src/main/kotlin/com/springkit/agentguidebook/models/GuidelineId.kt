package com.springkit.agentguidebook.models

/** 공백이 아닌 값을 지침 ID로 사용합니다. */
@JvmInline
value class GuidelineId(val value: String) {
  init {
    require(value.isNotBlank()) { "지침 ID는 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
