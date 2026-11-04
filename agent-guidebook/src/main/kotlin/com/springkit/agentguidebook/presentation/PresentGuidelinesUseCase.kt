package com.springkit.agentguidebook.presentation

import com.springkit.agentguidebook.domain.MatchedGuideline
import com.springkit.agentguidebook.search.SearchGuidelines
import com.springkit.agentguidebook.search.SearchRequest
import java.time.Clock

/**
 * 검색된 Guideline 가운데 Session에 아직 제공하지 않은 항목을 기록하고 반환합니다.
 *
 * 검색은 transaction 밖에서 실행하고 이력 선별과 기록은 UnitOfWork 안에서 조율합니다.
 */
class PresentGuidelinesUseCase(
    private val guidelineSearchService: SearchGuidelines,
    private val unitOfWork: UnitOfWork,
    private val presentationRepository: Repository,
    private val clock: Clock,
) {
  /**
   * 검색 결과에서 현재 Session에 새로 제공할 지침을 commit한 뒤 반환합니다.
   *
   * 검색 결과가 비어 있으면 UnitOfWork와 저장소에 접근하지 않습니다.
   */
  fun execute(request: PresentationRequest): List<MatchedGuideline> {
    val matchedGuidelines =
        guidelineSearchService.execute(SearchRequest(request.cwd, request.filepaths))
    if (matchedGuidelines.isEmpty()) {
      return emptyList()
    }

    return unitOfWork.execute {
      val history = presentationRepository.presentations(request.sessionId)
      val session = Session(request.sessionId, history)
      val newGuidelines = session.notPresented(matchedGuidelines)
      if (newGuidelines.isEmpty()) {
        return@execute emptyList()
      }

      val presentedAt = clock.instant()
      val presentations = newGuidelines.map { guideline ->
        Presentation(request.sessionId, guideline.id, presentedAt)
      }

      session.record(presentations)
      presentationRepository.record(presentations)
      newGuidelines
    }
  }
}
