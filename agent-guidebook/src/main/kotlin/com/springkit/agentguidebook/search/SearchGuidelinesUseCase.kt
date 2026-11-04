package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.GuidelineEvent
import com.springkit.agentguidebook.domain.GuidelineSearch
import com.springkit.agentguidebook.domain.MatchedGuideline
import java.nio.file.Path
import java.util.LinkedHashSet

class SearchGuidelinesUseCase(
    private val inputReader: InputReader,
) : SearchGuidelines {
  private val guidelineSearch = GuidelineSearch()

  /**
   * @throws [InvalidSearchRequestException] 프로젝트 또는 파일 경로가 요청 조건을 충족하지 않는 경우
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
