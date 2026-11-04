package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.domain.Guideline
import com.springkit.agentguidebook.domain.RepositoryIdentity
import com.springkit.agentguidebook.domain.SourceFile
import java.nio.file.Path

interface InputReader {
  /** @throws [InvalidSearchRequestException] 경로가 없거나 디렉터리가 아니거나 확인할 수 없는 경우 */
  fun validateWorkingDirectory(cwd: Path)

  /**
   * `.kt`와 `.kts` 파일을 요청 순서대로 UTF-8로 읽고 다른 확장자는 제외합니다.
   *
   * @throws [SourceReadException] 대상 파일을 확인하거나 읽을 수 없는 경우
   */
  fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile>

  /**
   * `guideline.yaml` 파일을 상대 경로순으로 읽습니다. 루트나 후보 파일이 없으면 빈 목록을 반환합니다.
   *
   * @throws [GuidelineLoadException] YAML 읽기나 지침 구조 검증에 실패한 경우
   */
  fun readGuidelines(root: Path): List<Guideline>

  /**
   * Git origin을 정규화된 owner/repository 식별자로 읽습니다.
   *
   * @throws [RepositoryIdentityUnavailableException] origin을 읽거나 식별자로 변환할 수 없는 경우
   */
  fun readRepositoryIdentity(cwd: Path): RepositoryIdentity
}
