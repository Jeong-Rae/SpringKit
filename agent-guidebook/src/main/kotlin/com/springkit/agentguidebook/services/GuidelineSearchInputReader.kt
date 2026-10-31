package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.errors.SourceReadException
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import java.nio.file.Path

/**
 * 검색 서비스가 프로젝트 파일, 지침 문서, 저장소 식별자를 읽는 입력 경계입니다.
 *
 * @see GuidelineSearchService
 */
interface GuidelineSearchInputReader {
  /**
   * 검색할 프로젝트 경로가 존재하는 디렉터리인지 확인합니다.
   *
   * @param cwd 절대 경로로 지정한 프로젝트 디렉터리
   * @throws [InvalidSearchRequestException] 경로가 없거나 디렉터리가 아니거나 확인할 수 없는 경우
   */
  fun validateWorkingDirectory(cwd: Path)

  /**
   * 요청된 Kotlin 파일을 전달된 경로 순서대로 읽습니다.
   *
   * @param filepaths 읽을 파일의 절대 경로 모음
   * @return UTF-8 내용과 언어가 연결된 소스 목록
   * @throws [SourceReadException] 대상 파일을 확인하거나 읽을 수 없는 경우
   */
  fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile>

  /**
   * 지침 루트 아래의 YAML을 상대 경로순으로 읽습니다.
   *
   * @param root 지침 파일을 탐색할 루트 경로
   * @return 검증된 지침 목록. 루트 또는 후보가 없으면 빈 목록입니다.
   * @throws [GuidelineLoadException] YAML 읽기나 지침 구조 검증에 실패한 경우
   */
  fun readGuidelines(root: Path): List<Guideline>

  /**
   * 프로젝트 Git 저장소의 origin을 정규화된 식별자로 읽습니다.
   *
   * @param cwd Git 저장소를 찾을 프로젝트 경로
   * @return origin에서 파생한 저장소 식별자
   * @throws [RepositoryIdentityUnavailableException] origin을 읽거나 식별자로 변환할 수 없는 경우
   */
  fun readRepositoryIdentity(cwd: Path): RepositoryIdentity
}
