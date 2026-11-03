package com.springkit.agentguidebook.search

import com.springkit.agentguidebook.search.filesystem.FileSystemInputReader

/** 실제 파일 시스템 입력을 사용하는 검색 Application을 조립합니다. */
object Factory {
  /** 프로젝트 파일, 지침 YAML과 Git 저장소를 읽는 검색 Application을 생성합니다. */
  fun fileSystem(): SearchGuidelinesUseCase = SearchGuidelinesUseCase(FileSystemInputReader())
}
