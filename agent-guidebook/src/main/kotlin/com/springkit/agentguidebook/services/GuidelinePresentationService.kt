package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.features.presentation.PresentGuidelinesUseCase
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import java.time.Clock

/** 기존 호출자에게 Session별 지침 제공 API를 유지하고 UseCase에 요청을 전달합니다. */
class GuidelinePresentationService(
    private val guidelineSearchService: GuidelineSearchService,
    private val unitOfWork: UnitOfWork,
    private val presentationRepository: PresentationRepository,
    private val clock: Clock,
) {
  private val presentGuidelinesUseCase =
      PresentGuidelinesUseCase(
          guidelineSearchService = guidelineSearchService,
          unitOfWork = unitOfWork,
          presentationRepository = presentationRepository,
          clock = clock,
      )

  /** Session에 새로 제공할 지침을 commit한 뒤 반환합니다. */
  fun present(request: GuidelinePresentationRequest): List<MatchedGuideline> =
      presentGuidelinesUseCase.execute(request)
}
