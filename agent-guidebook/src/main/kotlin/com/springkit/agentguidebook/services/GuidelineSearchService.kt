package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.GuidelineSearch
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import com.springkit.agentguidebook.storage.GuidelineReader
import com.springkit.agentguidebook.storage.RepositoryIdentityReader
import com.springkit.agentguidebook.storage.SourceFileReader
import java.nio.file.Files
import java.nio.file.Path

/** 프로젝트 파일에서 관련 지침을 검색하는 공개 API입니다. */
class GuidelineSearchService {
  private val sourceFileReader = SourceFileReader()
  private val guidelineReader = GuidelineReader()
  private val repositoryIdentityReader = RepositoryIdentityReader()
  private val guidelineSearch = GuidelineSearch()

  /** 요청의 Kotlin 파일과 프로젝트 지침을 읽어 관련 지침을 반환합니다. */
  fun search(request: GuidelineSearchRequest): List<MatchedGuideline> {
    val cwd = validateWorkingDirectory(request.cwd)
    val filepaths = normalizeFilepaths(request.filepaths)
    val sources = sourceFileReader.readKotlinSources(filepaths)

    if (sources.isEmpty()) {
      return emptyList()
    }

    val guidelines = guidelineReader.read(cwd.resolve(GUIDELINE_DIRECTORY))

    if (guidelines.isEmpty()) {
      return emptyList()
    }

    val repository = repositoryIdentityReader.read(cwd)
    return guidelineSearch.search(repository, FILE_CHANGED, sources, guidelines)
  }

  private fun validateWorkingDirectory(cwd: Path): Path {
    if (!cwd.isAbsolute) {
      throw InvalidSearchRequestException("프로젝트 경로는 절대 경로여야 합니다.")
    }

    val normalizedCwd = cwd.normalize()
    val isDirectory =
        try {
          Files.isDirectory(normalizedCwd)
        } catch (exception: SecurityException) {
          throw InvalidSearchRequestException("프로젝트 경로를 확인할 수 없습니다.", exception)
        }

    if (!isDirectory) {
      throw InvalidSearchRequestException("프로젝트 경로가 없거나 디렉터리가 아닙니다.")
    }

    return normalizedCwd
  }

  private fun normalizeFilepaths(filepaths: Collection<Path>): List<Path> {
    val normalizedFilepaths = LinkedHashSet<Path>()

    for (filepath in filepaths) {
      if (!filepath.isAbsolute) {
        throw InvalidSearchRequestException("검색할 파일 경로는 절대 경로여야 합니다.")
      }

      normalizedFilepaths.add(filepath.normalize())
    }

    return normalizedFilepaths.toList()
  }

  private companion object {
    const val GUIDELINE_DIRECTORY = ".agents/guides"
    val FILE_CHANGED = GuidelineEvent("file_changed")
  }
}
