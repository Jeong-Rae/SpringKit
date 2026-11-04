package com.springkit.agentguidebook.search.filesystem

import com.springkit.agentguidebook.domain.Guideline
import com.springkit.agentguidebook.domain.RepositoryIdentity
import com.springkit.agentguidebook.domain.SourceFile
import com.springkit.agentguidebook.search.InputReader
import com.springkit.agentguidebook.search.InvalidSearchRequestException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class FileSystemInputReader : InputReader {
  private val sourceFileReader = SourceFileReader()
  private val guidelineReader = GuidelineReader()
  private val repositoryIdentityReader = JGitRepositoryIdentityReader()

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

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      sourceFileReader.readKotlinSources(filepaths)

  override fun readGuidelines(root: Path): List<Guideline> = guidelineReader.read(root)

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity =
      repositoryIdentityReader.read(cwd)
}
