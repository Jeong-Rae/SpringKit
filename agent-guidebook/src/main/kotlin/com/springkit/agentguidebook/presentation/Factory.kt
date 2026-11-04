package com.springkit.agentguidebook.presentation

import com.springkit.agentguidebook.presentation.sqlite.SqliteConfig
import com.springkit.agentguidebook.presentation.sqlite.SqliteRepository
import com.springkit.agentguidebook.presentation.sqlite.SqliteUnitOfWork
import java.time.Clock

/** 파일 시스템 검색과 SQLite 이력 저장을 연결하는 지침 제공 Application을 조립합니다. */
object Factory {
  /** 검색, SQLite 이력 저장과 UTC 시스템 시계를 사용하는 Application을 반환합니다. */
  fun fileSystem(): PresentGuidelinesUseCase {
    val unitOfWork = SqliteUnitOfWork(SqliteConfig())

    return PresentGuidelinesUseCase(
        guidelineSearchService = com.springkit.agentguidebook.search.Factory.fileSystem(),
        unitOfWork = unitOfWork,
        presentationRepository = SqliteRepository(),
        clock = Clock.systemUTC(),
    )
  }
}
