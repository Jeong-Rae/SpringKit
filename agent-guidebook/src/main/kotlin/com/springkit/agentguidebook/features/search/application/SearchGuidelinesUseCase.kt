package com.springkit.agentguidebook.features.search.application

import com.springkit.agentguidebook.core.GuidelineSearch
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import java.nio.file.Path
import java.util.LinkedHashSet

/** 프로젝트 입력을 읽어 관련 Guideline을 검색하는 UseCase입니다. */
class SearchGuidelinesUseCase(
    private val inputReader: GuidelineSearchInputReader,
) : SearchGuidelines {
  private val guidelineSearch = GuidelineSearch()

  override fun execute(request: GuidelineSearchRequest): List<MatchedGuideline> {
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
