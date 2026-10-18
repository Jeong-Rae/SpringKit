package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import com.springkit.agentguidebook.services.GuidelineSearchInputReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 파일 시스템, 지침 YAML, Git에서 검색 입력을 읽습니다. */
internal class FileSystemGuidelineSearchInputReader : GuidelineSearchInputReader {
  private val sourceFileReader = SourceFileReader()
  private val guidelineReader = GuidelineReader()
  private val repositoryIdentityReader = RepositoryIdentityReader()

  /** 작업 경로가 실제 디렉터리인지 확인합니다. */
  override fun validateWorkingDirectory(cwd: Path) {
    val attributes =
        try {
          Files.readAttributes(cwd, BasicFileAttributes::class.java)
        } catch (exception: NoSuchFileException) {
          throw InvalidSearchRequestException("프로젝트 경로가 없습니다: $cwd", exception)
        } catch (exception: IOException) {
          throw InvalidSearchRequestException("프로젝트 경로를 확인할 수 없습니다: $cwd", exception)
        } catch (exception: SecurityException) {
          throw InvalidSearchRequestException("프로젝트 경로를 확인할 수 없습니다: $cwd", exception)
        }

    if (!attributes.isDirectory) {
      throw InvalidSearchRequestException("프로젝트 경로가 디렉터리가 아닙니다: $cwd")
    }
  }

  /** Kotlin 소스 파일을 입력 순서대로 읽습니다. */
  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      sourceFileReader.readKotlinSources(filepaths)

  /** 검색 가능한 지침 YAML을 상대 경로 순서대로 읽습니다. */
  override fun readGuidelines(root: Path): List<Guideline> = guidelineReader.read(root)

  /** Git origin에서 현재 저장소 식별자를 읽습니다. */
  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity =
      repositoryIdentityReader.read(cwd)
}
