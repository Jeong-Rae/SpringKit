package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.SessionId

class PresentationSession(
    val id: SessionId,
    history: Collection<Presentation>,
) {
  init {
    throw NotImplementedError("PresentationSession 동작은 아직 구현되지 않았습니다.")
  }

  fun notPresented(guidelines: Collection<MatchedGuideline>): List<MatchedGuideline> =
      throw NotImplementedError("미제공 Guideline 조회 동작은 아직 구현되지 않았습니다.")

  fun record(presentations: Collection<Presentation>) {
    throw NotImplementedError("Presentation 기록 동작은 아직 구현되지 않았습니다.")
  }
}
