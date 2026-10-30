package com.springkit.agentguidebook.storage

import org.jetbrains.exposed.v1.core.dao.id.CompositeID
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.CompositeEntity
import org.jetbrains.exposed.v1.dao.CompositeEntityClass

/**
 * SQLite Presentation 이력의 저장 열과 복합 키를 정의합니다.
 *
 * ORM 저장 구조는 도메인 `Presentation`과 분리합니다.
 */
internal object PresentationTable : CompositeIdTable("presentation") {
  val sessionId = text("session_id").entityId()
  val guidelineId = text("guideline_id").entityId()
  val presentedAt = text("presented_at")

  override val primaryKey = PrimaryKey(sessionId, guidelineId)
}

/**
 * Presentation 한 행을 기록하는 저장 전용 Exposed Entity입니다.
 *
 * 호출자에게 이 Entity를 반환하지 않습니다.
 */
internal class PresentationRow(id: EntityID<CompositeID>) : CompositeEntity(id) {
  companion object : CompositeEntityClass<PresentationRow>(PresentationTable)

  var presentedAt by PresentationTable.presentedAt
}
