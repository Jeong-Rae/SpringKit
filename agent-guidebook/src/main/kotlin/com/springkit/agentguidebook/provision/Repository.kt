package com.springkit.agentguidebook.provision

/** 각 메서드는 활성 UnitOfWork 경계 안에서 사용합니다. */
interface Repository {
  fun provisions(sessionId: SessionId): List<Provision>

  fun record(provisions: Collection<Provision>)
}
