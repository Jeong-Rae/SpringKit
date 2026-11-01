package com.springkit.agentguidebook.features.presentation

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.services.GuidelineSearchService
import com.springkit.agentguidebook.services.PresentationRepository
import com.springkit.agentguidebook.services.UnitOfWork
import java.time.Clock

class PresentGuidelinesUseCase(
    private val guidelineSearchService: GuidelineSearchService,
    private val unitOfWork: UnitOfWork,
    private val presentationRepository: PresentationRepository,
    private val clock: Clock,
) {
  fun execute(request: GuidelinePresentationRequest): List<MatchedGuideline> =
      throw NotImplementedError("PresentGuidelinesUseCase 계약 구현 전")
}
