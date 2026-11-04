package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.GuidelineEvent
import com.springkit.agentguidebook.domain.GuidelineSearch
import com.springkit.agentguidebook.domain.MatchedGuideline
import java.nio.file.Path
import java.util.LinkedHashSet

/** 프로젝트 입력을 읽고 도메인 검색 규칙으로 관련 Guideline을 반환하는 검색 Application입니다. */
class SearchGuidelinesUseCase(
    private val inputReader: InputReader,
) : SearchGuidelines {
  private val guidelineSearch = GuidelineSearch()

  /**
   * 요청한 Kotlin 파일과 프로젝트 지침을 읽어 관련 지침을 반환합니다.
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
  override fun execute(request: SearchRequest): List<MatchedGuideline> {
    validateAbsoluteWorkingDirectory(request.cwd)
    validateAbsoluteFilepaths(request.filepaths)

    val cwd = request.cwd.normalize()
    val filepaths = normalizeFilepaths(request.filepaths)

    inputReader.validateWorkingDirectory(cwd)

    val sources = inputReader.readKotlinSources(filepaths)

    val hasNoKotlinSources = sources.isEmpty()
    if (hasNoKotlinSources) {
      return emptyList()
    }

    val guidelines = inputReader.readGuidelines(cwd.resolve(GUIDELINE_DIRECTORY))

    val hasNoGuidelines = guidelines.isEmpty()
    if (hasNoGuidelines) {
      return emptyList()
    }

    val repository = inputReader.readRepositoryIdentity(cwd)
    return guidelineSearch.search(repository, FILE_CHANGED, sources, guidelines)
  }

  private fun validateAbsoluteWorkingDirectory(cwd: Path) {
    val hasRelativeWorkingDirectory = !cwd.isAbsolute
    if (hasRelativeWorkingDirectory) {
      throw InvalidSearchRequestException("프로젝트 경로는 절대 경로여야 합니다.")
    }
  }

  private fun validateAbsoluteFilepaths(filepaths: Collection<Path>) {
    val hasRelativeFilepath = filepaths.any { filepath -> !filepath.isAbsolute }
    if (hasRelativeFilepath) {
      throw InvalidSearchRequestException("검색할 파일 경로는 절대 경로여야 합니다.")
    }
  }

  private fun normalizeFilepaths(filepaths: Collection<Path>): List<Path> {
    val normalizedFilepaths = LinkedHashSet<Path>()

    for (filepath in filepaths) {
      normalizedFilepaths.add(filepath.normalize())
    }

    return normalizedFilepaths.toList()
  }

  private companion object {
    const val GUIDELINE_DIRECTORY = ".agents/guides"
    val FILE_CHANGED = GuidelineEvent("file_changed")
  }
}
