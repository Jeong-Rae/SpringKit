package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.errors.SourceReadException
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import com.springkit.agentguidebook.services.GuidelineSearchInputReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * 실제 프로젝트 파일, 지침 YAML, Git 저장소에서 검색 입력을 읽습니다.
 *
 * 파일 형식과 Git origin 해석은 각각 전용 reader에 위임합니다.
 */
internal class FileSystemGuidelineSearchInputReader : GuidelineSearchInputReader {
  private val sourceFileReader = SourceFileReader()
  private val guidelineReader = GuidelineReader()
  private val repositoryIdentityReader = RepositoryIdentityReader()

  /**
   * 프로젝트 경로가 실제 디렉터리인지 확인합니다.
   *
   * @param cwd 확인할 절대 프로젝트 경로
   * @throws [InvalidSearchRequestException] 경로가 없거나 디렉터리가 아니거나 접근할 수 없는 경우
   */
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

  /**
   * 지원하는 Kotlin 파일을 경로 입력 순서대로 읽습니다.
   *
   * @param filepaths 읽을 절대 파일 경로
   * @return UTF-8로 읽은 Kotlin 소스 목록
   * @throws [SourceReadException] 대상 파일을 확인하거나 읽을 수 없는 경우
   */
  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      sourceFileReader.readKotlinSources(filepaths)

  /**
   * 지침 YAML을 루트 기준 상대 경로 순서로 읽습니다.
   *
   * @param root 지침 파일을 탐색할 디렉터리
   * @return 검증된 지침 목록
   * @throws [GuidelineLoadException] 지침 루트에 접근할 수 없거나 YAML 구조·값·ID가 올바르지 않은 경우
   */
  override fun readGuidelines(root: Path): List<Guideline> = guidelineReader.read(root)

  /**
   * Git origin에서 현재 저장소의 정규화된 식별자를 읽습니다.
   *
   * @param cwd Git 저장소를 찾을 프로젝트 경로
   * @return owner와 repository로 구성한 저장소 식별자
   * @throws [RepositoryIdentityUnavailableException] origin을 확인하거나 해석할 수 없는 경우
   */
  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity =
      repositoryIdentityReader.read(cwd)
}
