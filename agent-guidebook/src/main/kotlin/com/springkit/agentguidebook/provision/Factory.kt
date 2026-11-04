package com.springkit.agentguidebook.provision

import com.springkit.agentguidebook.provision.sqlite.SqliteConfig
import com.springkit.agentguidebook.provision.sqlite.SqliteRepository
import com.springkit.agentguidebook.provision.sqlite.SqliteUnitOfWork
import java.time.Clock

object Factory {
  fun fileSystem(): ProvideGuidelinesUseCase {
    val unitOfWork = SqliteUnitOfWork(SqliteConfig())

    return ProvideGuidelinesUseCase(
        guidelineSearchService = com.springkit.agentguidebook.search.Factory.fileSystem(),
        unitOfWork = unitOfWork,
        provisionRepository = SqliteRepository(),
        clock = Clock.systemUTC(),
    )
  }
}
