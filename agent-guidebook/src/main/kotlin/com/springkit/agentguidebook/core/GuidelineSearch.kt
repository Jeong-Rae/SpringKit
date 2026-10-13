package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import java.util.ArrayList
import java.util.LinkedHashSet

/** 전달받은 지침과 소스에서 관련 지침을 검색합니다. */
class GuidelineSearch {

  fun search(
      repository: RepositoryIdentity,
      event: GuidelineEvent,
      sources: Collection<SourceFile>,
      guidelines: Collection<Guideline>,
  ): List<MatchedGuideline> {
    val matchedIds = LinkedHashSet<GuidelineId>()
    val matches = ArrayList<MatchedGuideline>()

    for (guideline in guidelines) {
      if (guideline.id in matchedIds) {
        continue
      }

      if (matchesAnySource(guideline, repository, event, sources)) {
        matchedIds.add(guideline.id)
        matches.add(MatchedGuideline(guideline.id, guideline.content))
      }
    }

    return matches
  }

  private fun matchesAnySource(
      guideline: Guideline,
      repository: RepositoryIdentity,
      event: GuidelineEvent,
      sources: Collection<SourceFile>,
  ): Boolean = sources.any { source ->
    val context = ApplicabilityContext(repository, source.language, event)
    guideline.isApplicable(context) && guideline.matches(source.content)
  }
}
