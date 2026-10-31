package com.springkit.agentguidebook.storage

import org.jetbrains.exposed.v1.core.Table

/**
 * SQLite Presentation 이력의 저장 열과 복합 키를 정의합니다.
 *
 * ORM 저장 구조는 도메인 `Presentation`과 분리합니다.
 */
internal object PresentationTable : Table("presentation") {
  val sessionId = text("session_id")
  val guidelineId = text("guideline_id")
  val presentedAt = text("presented_at")

  override val primaryKey = PrimaryKey(sessionId, guidelineId)
}
