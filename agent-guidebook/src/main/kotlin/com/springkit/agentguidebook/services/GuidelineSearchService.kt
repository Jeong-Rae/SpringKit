package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.errors.SourceReadException
import com.springkit.agentguidebook.features.search.application.SearchGuidelines
import com.springkit.agentguidebook.features.search.application.SearchGuidelinesUseCase
import com.springkit.agentguidebook.models.GuidelineSearchRequest

/**
 * 프로젝트 입력을 읽고 변경 대상 파일과 관련된 지침을 검색하는 공개 API입니다.
 *
 * 저장소 식별자 조회는 Kotlin 소스와 지침 후보가 모두 있을 때만 수행합니다.
 *
 * @param inputReader 검색 요청에 필요한 프로젝트 입력을 읽는 경계
 */
class GuidelineSearchService(
    private val inputReader: GuidelineSearchInputReader,
) : SearchGuidelines {
  private val searchGuidelinesUseCase = SearchGuidelinesUseCase(inputReader)

  /**
   * 요청의 Kotlin 파일과 프로젝트 지침을 읽어 관련 지침을 반환합니다.
   *
   * 소스 또는 지침 후보가 없으면 저장소 식별자를 읽지 않고 빈 결과를 반환합니다.
   *
   * @param request 검색할 프로젝트와 절대 파일 경로
   * @return 검색 순서가 유지된 관련 지침 목록
   * @throws [InvalidSearchRequestException] 프로젝트 또는 파일 경로가 검색 요청 계약에 맞지 않는 경우
   * @throws [SourceReadException] 대상 Kotlin 파일을 확인하거나 읽을 수 없는 경우
   * @throws [GuidelineLoadException] 지침 루트를 읽거나 지침 내용을 검증할 수 없는 경우
   * @throws [RepositoryIdentityUnavailableException] 필요한 Git 저장소 식별자를 얻을 수 없는 경우
   */
  fun search(request: GuidelineSearchRequest): List<MatchedGuideline> = execute(request)

  override fun execute(request: GuidelineSearchRequest): List<MatchedGuideline> =
      searchGuidelinesUseCase.execute(request)
}
