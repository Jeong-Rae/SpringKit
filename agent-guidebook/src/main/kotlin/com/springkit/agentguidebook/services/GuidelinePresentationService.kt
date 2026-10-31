package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.core.PresentationSession
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import java.time.Clock

/**
 * 검색 결과에서 Session에 새로 제공할 지침을 조율하는 Application 서비스입니다.
 *
 * 검색은 transaction 밖에서 끝내고 도메인 선별과 저장을 UnitOfWork 안에서 조율합니다.
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
   * 검색 결과가 없으면 저장 경계에 진입하지 않습니다.
   */
  fun present(request: GuidelinePresentationRequest): List<MatchedGuideline> {
    val matchedGuidelines =
        guidelineSearchService.search(GuidelineSearchRequest(request.cwd, request.filepaths))
    val hasMatchedGuidelines = matchedGuidelines.isNotEmpty()
    if (!hasMatchedGuidelines) {
      return emptyList()
    }

    return unitOfWork.execute {
      val history = presentationRepository.presentations(request.sessionId)
      val session = PresentationSession(request.sessionId, history)
      val newGuidelines = session.notPresented(matchedGuidelines)
      val hasNewGuidelines = newGuidelines.isNotEmpty()

      if (!hasNewGuidelines) {
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
