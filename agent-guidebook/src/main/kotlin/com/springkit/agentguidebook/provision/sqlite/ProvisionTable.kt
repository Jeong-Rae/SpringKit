package com.springkit.agentguidebook.provision.sqlite

import org.jetbrains.exposed.v1.core.Table

internal object ProvisionTable : Table("provision") {
  val sessionId = text("session_id")
  val guidelineId = text("guideline_id")
  val providedAt = text("provided_at")

  override val primaryKey = PrimaryKey(sessionId, guidelineId)
}
