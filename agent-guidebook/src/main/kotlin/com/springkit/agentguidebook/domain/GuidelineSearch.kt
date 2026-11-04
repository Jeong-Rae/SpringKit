package com.springkit.agentguidebook.domain

import java.util.ArrayList
import java.util.LinkedHashSet

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
      val isAlreadyMatched = guideline.id in matchedIds
      if (isAlreadyMatched) {
        continue
      }

      val hasMatchingSource = matchesAnySource(guideline, repository, event, sources)
      if (hasMatchingSource) {
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
    val isApplicable = guideline.isApplicable(context)
    val hasMatchingContent = isApplicable && guideline.matches(source.content)

    hasMatchingContent
  }
}
