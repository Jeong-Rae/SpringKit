package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.GuidelineSearch
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import java.nio.file.Path
import java.util.LinkedHashSet

/** 프로젝트 파일에서 관련 지침을 검색하는 공개 API입니다. */
class GuidelineSearchService(
    private val inputReader: GuidelineSearchInputReader,
) {
  private val guidelineSearch = GuidelineSearch()

  /** 요청의 Kotlin 파일과 프로젝트 지침을 읽어 관련 지침을 반환합니다. */
  fun search(request: GuidelineSearchRequest): List<MatchedGuideline> {
    validateAbsoluteWorkingDirectory(request.cwd)
    validateAbsoluteFilepaths(request.filepaths)

    val cwd = request.cwd.normalize()
    val filepaths = normalizeFilepaths(request.filepaths)

    inputReader.validateWorkingDirectory(cwd)

    val sources = inputReader.readKotlinSources(filepaths)

    if (sources.isEmpty()) {
      return emptyList()
    }

    val guidelines = inputReader.readGuidelines(cwd.resolve(GUIDELINE_DIRECTORY))

    if (guidelines.isEmpty()) {
      return emptyList()
    }

    val repository = inputReader.readRepositoryIdentity(cwd)
    return guidelineSearch.search(repository, FILE_CHANGED, sources, guidelines)
  }

  private fun validateAbsoluteWorkingDirectory(cwd: Path) {
    if (!cwd.isAbsolute) {
      throw InvalidSearchRequestException("프로젝트 경로는 절대 경로여야 합니다.")
    }
  }

  private fun validateAbsoluteFilepaths(filepaths: Collection<Path>) {
    if (filepaths.any { filepath -> !filepath.isAbsolute }) {
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
