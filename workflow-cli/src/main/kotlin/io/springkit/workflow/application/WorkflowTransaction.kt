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
 * Coordinates state persistence and compensatable adapter effects without knowing their provider.
 *
 * The store owns the transaction. Provider changes are compensated in reverse order when the
 * application cannot persist the resulting state. A commit failure is deliberately not compensated:
 * the store may already have committed and must resolve the ambiguous result by transaction id.
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

    val snapshotResult = store.snapshot(StoreSnapshotRequest())
    if (snapshotResult is PortResult.Failure) {
      store.rollback(request)
      return snapshotResult.toWorkflowFailure(FailureCode.STORE_FAILURE)
    }
    snapshotResult as PortResult.Success

    return when (val mutation = operation(snapshotResult.value.snapshot)) {
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
