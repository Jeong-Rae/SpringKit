package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.domain.MatchedGuideline

class Session(
    val id: SessionId,
    history: Collection<Provision>,
) {
  private val provisionsByGuidelineId = LinkedHashMap<GuidelineId, Provision>()

  init {
    val suppliedHistory = history.toList()
    val historyGuidelineIds = mutableSetOf<GuidelineId>()

    for (provision in suppliedHistory) {
      require(provision.sessionId == id) { "다른 Session의 Provision 이력은 포함할 수 없습니다." }
      require(historyGuidelineIds.add(provision.guidelineId)) {
        "같은 Guideline ID의 Provision 이력은 중복될 수 없습니다."
      }
    }

    for (provision in suppliedHistory) {
      provisionsByGuidelineId[provision.guidelineId] = provision
    }
  }

  fun notProvided(guidelines: Collection<MatchedGuideline>): List<MatchedGuideline> {
    val consideredGuidelineIds = mutableSetOf<GuidelineId>()
    val notProvidedGuidelines = mutableListOf<MatchedGuideline>()

    for (guideline in guidelines) {
      val isAlreadyProvided = provisionsByGuidelineId.containsKey(guideline.id)
      val isFirstOccurrence = consideredGuidelineIds.add(guideline.id)

      if (!isAlreadyProvided && isFirstOccurrence) {
        notProvidedGuidelines.add(guideline)
      }
    }

    return notProvidedGuidelines
  }

  /** 잘못된 Session ID나 중복 Guideline ID가 있으면 상태를 바꾸지 않습니다. */
  fun record(provisions: Collection<Provision>) {
    val requestedProvisions = provisions.toList()
    val requestedGuidelineIds = mutableSetOf<GuidelineId>()

    for (provision in requestedProvisions) {
      require(provision.sessionId == id) { "다른 Session의 Provision은 기록할 수 없습니다." }
      require(requestedGuidelineIds.add(provision.guidelineId)) {
        "한 기록 명령에서 같은 Guideline ID를 중복할 수 없습니다."
      }
    }

    val includesPreviouslyProvidedGuideline =
        requestedGuidelineIds.any(provisionsByGuidelineId::containsKey)
    check(!includesPreviouslyProvidedGuideline) {
      "이미 제공한 Guideline ID는 다시 기록할 수 없습니다."
    }

    for (provision in requestedProvisions) {
      provisionsByGuidelineId[provision.guidelineId] = provision
    }
  }
}
