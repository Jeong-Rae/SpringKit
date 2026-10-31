package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.ApplicabilityContext
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * 저장소, 이벤트, 소스 코드에 적용되는 지침을 계산합니다.
 *
 * 검색은 전달받은 컬렉션만 사용하며 파일이나 저장소에 접근하지 않습니다.
 */
class GuidelineSearch {

  /**
   * 하나 이상의 소스와 관련된 지침을 입력 순서로 반환합니다.
   *
   * 같은 Guideline ID가 여러 소스에서 일치해도 첫 결과만 포함합니다.
   *
   * @param repository 검색 대상 저장소
   * @param event 현재 검색을 유발한 이벤트
   * @param sources 관련성을 확인할 소스 파일 모음
   * @param guidelines 적용 여부를 평가할 지침 모음
   * @return 적용되고 소스 내용과 일치하는 지침의 순서 보존 결과
   */
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
