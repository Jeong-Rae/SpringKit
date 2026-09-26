package io.springkit.workflow.application

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult

data class TransactionMutation<T>(
    val data: T,
    val snapshot: WorkflowStoreSnapshot,
    val changes: List<ChangeReceipt> = emptyList(),
)

/**
 * 공급자를 알지 못한 채 상태 저장과 어댑터 변경의 보상을 조정합니다.
 *
 * 트랜잭션은 저장소가 소유합니다. 애플리케이션이 결과 상태를 저장하지 못하면 공급자 변경을 역순으로 보상합니다. 커밋 실패는 저장소가 이미 커밋했을 수 있으므로 보상하지 않고
 * 트랜잭션 ID로 모호한 결과를 해결합니다.
 */
class WorkflowTransaction(
    private val store: WorkflowStorePort,
    private val compensation: CompensationPort,
) {
  fun <T> execute(
      request: StoreTransactionRequest,
      operation: (WorkflowStoreSnapshot) -> PortResult<TransactionMutation<T>>,
  ): WorkflowResult<T> {
    val begun = store.begin(request)
    if (begun is PortResult.Failure) return begun.toWorkflowFailure(FailureCode.STORE_FAILURE)

    val snapshot =
        when (val result = store.snapshot(StoreSnapshotRequest())) {
          is PortResult.Failure -> {
            store.rollback(request)
            return result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          }
          is PortResult.Success -> result.value.snapshot
        }
    return when (val mutation = operation(snapshot)) {
      is PortResult.Failure -> {
        mutation.change?.let { compensate(listOf(it), mutation.error.message) }
        store.rollback(request)
        mutation.toWorkflowFailure(FailureCode.EXTERNAL_FAILURE)
      }
      is PortResult.Success -> persist(request, mutation.value)
    }
  }

  private fun <T> persist(
      request: StoreTransactionRequest,
      mutation: TransactionMutation<T>,
  ): WorkflowResult<T> {
    val write =
        store.write(
            StoreWriteRequest(
                transactionId = request.transactionId,
                expectedRevision = mutation.snapshot.revision,
                snapshot = mutation.snapshot,
            )
        )
    if (write is PortResult.Failure) {
      compensate(mutation.changes, write.error.message)
      store.rollback(request)
      return write.toWorkflowFailure(FailureCode.STORE_FAILURE)
    }

    val committed = store.commit(request)
    if (committed is PortResult.Failure) {
      return committed.toWorkflowFailure(FailureCode.STORE_FAILURE)
    }
    return WorkflowResult.Success(mutation.data)
  }

  private fun compensate(changes: List<ChangeReceipt>, reason: String) {
    changes.asReversed().forEach { change ->
      if (change.compensation != null) {
        compensation.compensate(CompensateRequest(change, reason))
      }
    }
  }
}

fun PortResult.Failure.toWorkflowFailure(fallback: FailureCode): WorkflowResult.Failure {
  val code = FailureCode.entries.firstOrNull { it.name == error.code } ?: fallback
  return WorkflowResult.Failure(
      FailureData(
          code = code,
          message = error.message,
          blockedBy =
              error.target?.let { listOf(BlockedBy(error.code, error.message, it)) }.orEmpty(),
      )
  )
}
