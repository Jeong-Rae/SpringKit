package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 검색에 사용할 적용 규칙과 에이전트에게 전달할 지침 본문을 보유합니다.
 *
 * 이벤트 입력은 복사해 수정할 수 없는 집합으로 제공합니다.
 *
 * @param id 지침을 구분하는 식별자
 * @param scope 적용할 저장소와 언어 조건
 * @param events 적용할 이벤트 모음
 * @param match 소스 코드 관련성 조건
 * @param content 에이전트에게 전달할 비어 있지 않은 본문
 * @throws IllegalArgumentException 본문이 비어 있거나 공백뿐인 경우
 */
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

  /**
   * 저장소·언어 범위와 이벤트가 모두 일치하는지 판단합니다.
   *
   * @param context 적용 여부를 평가할 저장소, 언어, 이벤트
   * @return 범위와 이벤트가 모두 일치하면 `true`
   */
  fun isApplicable(context: ApplicabilityContext): Boolean {
    val matchesScope = scope.matches(context)
    val matchesEvent = events.contains(context.event)

    return matchesScope && matchesEvent
  }

  /**
   * 보유한 MatchCondition으로 소스 코드가 지침과 관련되는지 판단합니다.
   *
   * @param source 관련성을 확인할 소스 코드
   * @return 소스 내용에서 조건이 부분 일치하면 `true`
   */
  fun matches(source: String): Boolean = match.matches(source)

  override fun equals(other: Any?): Boolean =
      this === other || (other is Guideline && id == other.id)

  override fun hashCode(): Int = id.hashCode()
}
