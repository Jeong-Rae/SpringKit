package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import java.util.Collections
import java.util.LinkedHashSet

/** 적용 범위와 소스 코드 관련성 조건, 에이전트에게 전달할 본문을 보유합니다. */
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

  /** 저장소, 언어, 이벤트 조건이 모두 충족될 때 적용됩니다. */
  fun isApplicable(context: ApplicabilityContext): Boolean =
      scope.matches(context) && events.contains(context.event)

  /** 보유한 MatchCondition으로 소스 코드의 관련성을 판단합니다. */
  fun matches(source: String): Boolean = match.matches(source)

  override fun equals(other: Any?): Boolean =
      this === other || (other is Guideline && id == other.id)

  override fun hashCode(): Int = id.hashCode()
}
