package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.domain.MatchedGuideline

/**
 * 한 Session에서 제공한 지침 이력과 미제공 지침 판단을 소유합니다.
 *
 * 이력의 중복 기준은 Guideline ID이며, 제공 시각은 중복 여부에 영향을 주지 않습니다.
 */
class Session(
    val id: SessionId,
    history: Collection<Provision>,
) {
  private val provisionsByGuidelineId = LinkedHashMap<GuidelineId, Provision>()

  init {
    val suppliedHistory = history.toList()
    val historyGuidelineIds = mutableSetOf<GuidelineId>()

    for (provision in suppliedHistory) {
      require(provision.sessionId == id) { "다른 Session의 Provision 이력은 포함할 수 없습니다." }
      require(historyGuidelineIds.add(provision.guidelineId)) {
        "같은 Guideline ID의 Provision 이력은 중복될 수 없습니다."
      }
    }

    for (provision in suppliedHistory) {
      provisionsByGuidelineId[provision.guidelineId] = provision
    }
  }

  /**
   * 이미 제공한 지침을 제외하고 입력 순서대로 첫 항목을 반환합니다.
   *
   * 조회 중 Session 이력과 입력 컬렉션은 변경하지 않습니다.
   */
  fun notProvided(guidelines: Collection<MatchedGuideline>): List<MatchedGuideline> {
    val consideredGuidelineIds = mutableSetOf<GuidelineId>()
    val notProvidedGuidelines = mutableListOf<MatchedGuideline>()

    for (guideline in guidelines) {
      val isAlreadyProvided = provisionsByGuidelineId.containsKey(guideline.id)
      val isFirstOccurrence = consideredGuidelineIds.add(guideline.id)

      if (!isAlreadyProvided && isFirstOccurrence) {
        notProvidedGuidelines.add(guideline)
      }
    }

    return notProvidedGuidelines
  }

  /**
   * 새 Provision 이력을 검증한 뒤 Session 상태에 추가합니다.
   *
   * 인자 오류나 기존 이력과의 중복이 있으면 상태를 바꾸지 않습니다.
   */
  fun record(provisions: Collection<Provision>) {
    val requestedProvisions = provisions.toList()
    val requestedGuidelineIds = mutableSetOf<GuidelineId>()

    for (provision in requestedProvisions) {
      require(provision.sessionId == id) { "다른 Session의 Provision은 기록할 수 없습니다." }
      require(requestedGuidelineIds.add(provision.guidelineId)) {
        "한 기록 명령에서 같은 Guideline ID를 중복할 수 없습니다."
      }
    }

    val includesPreviouslyProvidedGuideline =
        requestedGuidelineIds.any(provisionsByGuidelineId::containsKey)
    check(!includesPreviouslyProvidedGuideline) {
      "이미 제공한 Guideline ID는 다시 기록할 수 없습니다."
    }

    for (provision in requestedProvisions) {
      provisionsByGuidelineId[provision.guidelineId] = provision
    }
  }
}
