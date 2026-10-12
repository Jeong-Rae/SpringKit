package com.springkit.agentguidebook.models

/** 지침 적용 여부를 판단할 이벤트 이름을 표현합니다. */
@JvmInline
value class GuidelineEvent(val value: String) {
  init {
    require(value.isNotBlank()) { "이벤트 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
