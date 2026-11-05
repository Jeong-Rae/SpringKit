package com.springkit.agentguidebook.domain

import java.util.Collections
import java.util.LinkedHashSet

class Guideline(
    val id: GuidelineId,
    val scope: Scope,
    events: Collection<GuidelineEvent>,
    val match: MatchCondition,
    val content: String,
) {
  val events: Set<GuidelineEvent> = Collections.unmodifiableSet(LinkedHashSet(events))

  init {
    require(content.isNotBlank()) { "지침 본문은 비어 있거나 공백으로만 구성될 수 없습니다." }
  }

  fun isApplicable(context: ApplicabilityContext): Boolean {
    val matchesScope = scope.matches(context)
    val matchesEvent = events.contains(context.event)

    return matchesScope && matchesEvent
  }

  fun matches(source: String): Boolean = match.matches(source)

  override fun equals(other: Any?): Boolean =
      this === other || (other is Guideline && id == other.id)

  override fun hashCode(): Int = id.hashCode()
}
