package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.domain.MatchedGuideline
import com.springkit.agentguidebook.search.SearchGuidelines
import com.springkit.agentguidebook.search.SearchRequest
import java.time.Clock

/**
 * 검색된 Guideline 가운데 Session에 아직 제공하지 않은 항목을 기록하고 반환합니다.
 *
 * 검색은 transaction 밖에서 실행하고 이력 선별과 기록은 UnitOfWork 안에서 조율합니다.
 */
class ProvideGuidelinesUseCase(
    private val guidelineSearchService: SearchGuidelines,
    private val unitOfWork: UnitOfWork,
    private val provisionRepository: Repository,
    private val clock: Clock,
) {
  /**
   * 검색 결과에서 현재 Session에 새로 제공할 지침을 commit한 뒤 반환합니다.
   *
   * 검색 결과가 비어 있으면 UnitOfWork와 저장소에 접근하지 않습니다.
   */
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
