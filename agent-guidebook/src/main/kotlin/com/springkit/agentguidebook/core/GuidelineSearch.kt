package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile

/** 전달받은 지침과 소스에서 관련 지침을 검색합니다. */
class GuidelineSearch {

  fun search(
      repository: RepositoryIdentity,
      event: GuidelineEvent,
      sources: Collection<SourceFile>,
      guidelines: Collection<Guideline>,
  ): List<MatchedGuideline> = throw NotImplementedError("핵심 지침 검색은 아직 구현되지 않았습니다.")
}
