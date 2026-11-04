package com.springkit.agentguidebook.provision

@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "Session 식별자는 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
