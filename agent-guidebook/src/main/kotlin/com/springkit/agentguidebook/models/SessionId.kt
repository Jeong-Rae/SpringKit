package com.springkit.agentguidebook.models

/**
 * 지침 제공 이력을 구분하는 Session 식별자입니다.
 *
 * 공백이 아닌 값을 요구하며 전달된 문자열은 그대로 보존합니다.
 */
@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "Session 식별자는 비어 있거나 공백으로만 구성될 수 없습니다." }
  }
}
