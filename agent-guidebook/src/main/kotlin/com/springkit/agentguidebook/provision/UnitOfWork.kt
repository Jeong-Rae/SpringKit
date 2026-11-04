package com.springkit.agentguidebook.provision

/** callback이 실행된 transaction이 commit된 뒤 결과를 반환하고 실패는 호출자에게 전달합니다. */
interface UnitOfWork {
  fun <T> execute(operation: () -> T): T
}
