package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.time.Clock

/**
 * 한 Session에 아직 제공하지 않은 지침을 검색하고 기록하는 Application 서비스입니다.
 *
 * 검색 결과는 Session 이력을 반영한 뒤 호출자에게 전달합니다.
 */
class GuidelinePresentationService(
    private val guidelineSearchService: GuidelineSearchService,
    private val presentationRepository: PresentationRepository,
    private val clock: Clock,
) {
  /**
   * 검색 결과에서 Session에 새로 제공할 지침만 기록한 뒤 반환합니다.
   *
   * 반환은 Presentation 이력 commit이 성공한 뒤에만 완료됩니다.
   */
  fun present(request: GuidelinePresentationRequest): List<MatchedGuideline> =
      throw NotImplementedError("Guideline Presentation 동작은 아직 구현되지 않았습니다.")
}
