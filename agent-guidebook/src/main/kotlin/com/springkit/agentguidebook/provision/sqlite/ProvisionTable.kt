package com.springkit.agentguidebook.provision.sqlite

import org.jetbrains.exposed.v1.core.Table

/**
 * SQLite Provision 이력의 저장 열과 복합 키를 정의합니다.
 *
 * ORM 저장 구조는 도메인 `Provision`과 분리합니다.
 */
internal object ProvisionTable : Table("provision") {
  val sessionId = text("session_id")
  val guidelineId = text("guideline_id")
  val providedAt = text("provided_at")

  override val primaryKey = PrimaryKey(sessionId, guidelineId)
}
