package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SourceFile
import java.nio.file.Path

/** 지침 검색 서비스가 작업 디렉터리, Kotlin 소스, 지침, 저장소 식별자를 읽는 경계입니다. */
interface GuidelineSearchInputReader {
  /** 작업 디렉터리가 존재하는 디렉터리인지 확인합니다. */
  fun validateWorkingDirectory(cwd: Path)

  /** 절대 경로로 지정한 Kotlin 소스를 입력 순서대로 읽습니다. */
  fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile>

  /** 지침 루트 아래의 지침을 상대 경로 순으로 읽습니다. */
  fun readGuidelines(root: Path): List<Guideline>

  /** 작업 디렉터리 Git origin에서 저장소 식별자를 읽습니다. */
  fun readRepositoryIdentity(cwd: Path): RepositoryIdentity
}
