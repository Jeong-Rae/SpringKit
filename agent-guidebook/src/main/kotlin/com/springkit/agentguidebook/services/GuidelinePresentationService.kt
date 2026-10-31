package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.time.Clock

/**
 * 검색 결과에서 Session에 새로 제공할 지침을 조율하는 Application 서비스입니다.
 *
 * 검색, 도메인 선별과 저장소 호출을 연결합니다.
 */
class GuidelinePresentationService(
    private val guidelineSearchService: GuidelineSearchService,
    private val unitOfWork: UnitOfWork,
    private val presentationRepository: PresentationRepository,
    private val clock: Clock,
) {
  /**
   * Session에 아직 제공하지 않은 지침을 commit한 뒤 반환합니다.
   *
   * 구현은 다음 Step에서 추가합니다.
   */
  fun present(request: GuidelinePresentationRequest): List<MatchedGuideline> =
      throw NotImplementedError("Presentation Application 동작은 구현 단계에서 추가합니다.")
}
