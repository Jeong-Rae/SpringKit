package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.domain.MatchedGuideline
import com.springkit.agentguidebook.search.SearchGuidelines
import com.springkit.agentguidebook.search.SearchRequest
import java.time.Clock

class ProvideGuidelinesUseCase(
    private val guidelineSearchService: SearchGuidelines,
    private val unitOfWork: UnitOfWork,
    private val provisionRepository: Repository,
    private val clock: Clock,
) {
  fun execute(request: ProvisionRequest): List<MatchedGuideline> {
    val matchedGuidelines =
        guidelineSearchService.execute(SearchRequest(request.cwd, request.filepaths))
    if (matchedGuidelines.isEmpty()) {
      return emptyList()
    }

    return unitOfWork.execute {
      val history = provisionRepository.provisions(request.sessionId)
      val session = Session(request.sessionId, history)
      val newGuidelines = session.notProvided(matchedGuidelines)
      if (newGuidelines.isEmpty()) {
        return@execute emptyList()
      }

      val providedAt = clock.instant()
      val provisions = newGuidelines.map { guideline ->
        Provision(request.sessionId, guideline.id, providedAt)
      }

      session.record(provisions)
      provisionRepository.record(provisions)
      newGuidelines
    }
  }
}
