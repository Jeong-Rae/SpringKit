package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId

/**
 * 한 Session에서 제공한 지침 이력과 미제공 지침 판단을 소유합니다.
 *
 * 이력의 중복 기준은 Guideline ID이며, 제공 시각은 중복 여부에 영향을 주지 않습니다.
 */
class PresentationSession(
    val id: SessionId,
    history: Collection<Presentation>,
) {
  private val presentationsByGuidelineId = LinkedHashMap<GuidelineId, Presentation>()

  init {
    val suppliedHistory = history.toList()
    val historyGuidelineIds = mutableSetOf<GuidelineId>()

    for (presentation in suppliedHistory) {
      require(presentation.sessionId == id) { "다른 Session의 Presentation 이력은 포함할 수 없습니다." }
      require(historyGuidelineIds.add(presentation.guidelineId)) {
        "같은 Guideline ID의 Presentation 이력은 중복될 수 없습니다."
      }
    }

    for (presentation in suppliedHistory) {
      presentationsByGuidelineId[presentation.guidelineId] = presentation
    }
  }

  /**
   * 이미 제공한 지침을 제외하고 입력 순서대로 첫 항목을 반환합니다.
   *
   * 조회 중 Session 이력과 입력 컬렉션은 변경하지 않습니다.
   */
  fun notPresented(guidelines: Collection<MatchedGuideline>): List<MatchedGuideline> {
    val consideredGuidelineIds = mutableSetOf<GuidelineId>()
    val notPresentedGuidelines = mutableListOf<MatchedGuideline>()

    for (guideline in guidelines) {
      val isAlreadyPresented = presentationsByGuidelineId.containsKey(guideline.id)
      val isFirstOccurrence = consideredGuidelineIds.add(guideline.id)

      if (!isAlreadyPresented && isFirstOccurrence) {
        notPresentedGuidelines.add(guideline)
      }
    }

    return notPresentedGuidelines
  }

  /**
   * 새 Presentation 이력을 검증한 뒤 Session 상태에 추가합니다.
   *
   * 인자 오류나 기존 이력과의 중복이 있으면 상태를 바꾸지 않습니다.
   */
  fun record(presentations: Collection<Presentation>) {
    val requestedPresentations = presentations.toList()
    val requestedGuidelineIds = mutableSetOf<GuidelineId>()

    for (presentation in requestedPresentations) {
      require(presentation.sessionId == id) { "다른 Session의 Presentation은 기록할 수 없습니다." }
      require(requestedGuidelineIds.add(presentation.guidelineId)) {
        "한 기록 명령에서 같은 Guideline ID를 중복할 수 없습니다."
      }
    }

    val includesPreviouslyPresentedGuideline =
        requestedGuidelineIds.any(presentationsByGuidelineId::containsKey)
    check(!includesPreviouslyPresentedGuideline) {
      "이미 제공한 Guideline ID는 다시 기록할 수 없습니다."
    }

    for (presentation in requestedPresentations) {
      presentationsByGuidelineId[presentation.guidelineId] = presentation
    }
  }
}
