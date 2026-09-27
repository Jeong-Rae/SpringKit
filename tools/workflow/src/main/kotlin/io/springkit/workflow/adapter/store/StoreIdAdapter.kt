package io.springkit.workflow.adapter.store

import io.springkit.workflow.application.IdKind
import io.springkit.workflow.application.IdPort
import io.springkit.workflow.application.IssueIdRequest
import io.springkit.workflow.application.IssueIdResponse
import io.springkit.workflow.application.IssuedId
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreTransactionState
import io.springkit.workflow.application.StoreWriteRequest
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.domain.IdSequence
import java.util.UUID

/*
 * Workflow Store의 sequence를 트랜잭션으로 증가시켜 식별자를 발급합니다.
 */
class StoreIdAdapter(private val store: WorkflowStorePort) : IdPort {
  override fun issue(request: IssueIdRequest): PortResult<IssueIdResponse> =
      synchronized(store) {
        when (request.kind) {
          IdKind.REVIEW_REVISION,
          IdKind.CHANGE_REVISION,
          IdKind.THREAD,
          IdKind.COMMENT,
          IdKind.MERGE_QUEUE,
          IdKind.CANDIDATE,
          IdKind.RELEASE,
          IdKind.AUDIT -> issueSequenced(request)
          else -> issueInfrastructure(request)
        }
      }

  private fun issueSequenced(request: IssueIdRequest): PortResult<IssueIdResponse> {
    val transactionId = "id-${UUID.randomUUID()}"
    val transaction = StoreTransactionRequest(transactionId, idempotencyKey = transactionId)
    when (val begun = store.begin(transaction)) {
      is PortResult.Failure -> return begun
      is PortResult.Success ->
          if (begun.value.state != StoreTransactionState.OPEN) {
            return failure("ID_TRANSACTION_NOT_OPEN", "ID 발급 트랜잭션을 열 수 없습니다.")
          }
    }

    val current =
        when (val result = store.snapshot(StoreSnapshotRequest())) {
          is PortResult.Failure -> return rollbackAfterFailure(transaction, result)
          is PortResult.Success -> result.value.snapshot
        }
    val currentValue = current.sequence.valueOf(request.kind)
    val lastValue = currentValue.let { value ->
      if (value > Long.MAX_VALUE - request.count.toLong()) {
        return rollbackAfterFailure(
            transaction,
            failure("ID_SEQUENCE_EXHAUSTED", "식별자 sequence가 최대값에 도달했습니다."),
        )
      }
      value + request.count
    }
    val updated = current.copy(sequence = current.sequence.withValue(request.kind, lastValue))
    val written =
        store.write(
            StoreWriteRequest(
                transactionId = transactionId,
                expectedRevision = current.revision,
                snapshot = updated,
            )
        )
    if (written is PortResult.Failure) return rollbackAfterFailure(transaction, written)

    val committed = store.commit(transaction)
    if (committed is PortResult.Failure) return rollbackAfterFailure(transaction, committed)

    val ids =
        (currentValue + 1..lastValue).map { value ->
          IssuedId(request.kind, "${request.kind.prefix()}-$value")
        }
    return PortResult.Success(IssueIdResponse(ids))
  }

  private fun issueInfrastructure(request: IssueIdRequest): PortResult<IssueIdResponse> {
    val suffix = sanitize(request.requestId ?: request.kind.name.lowercase())
    val ids =
        (1..request.count).map {
          IssuedId(request.kind, "${request.kind.prefix()}-${UUID.randomUUID()}-$suffix")
        }
    return PortResult.Success(IssueIdResponse(ids))
  }

  private fun rollbackAfterFailure(
      transaction: StoreTransactionRequest,
      failure: PortResult.Failure,
  ): PortResult.Failure {
    store.rollback(transaction)
    return failure
  }

  private fun failure(code: String, message: String): PortResult.Failure =
      PortResult.Failure(PortError(code, message, retryable = code == "ID_SEQUENCE_EXHAUSTED"))

  private fun sanitize(value: String): String =
      value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "request" }

  private fun IdKind.prefix(): String =
      when (this) {
        IdKind.REVIEW_REVISION -> "rv"
        IdKind.CHANGE_REVISION -> "cr"
        IdKind.THREAD -> "thread"
        IdKind.COMMENT -> "comment"
        IdKind.MERGE_QUEUE -> "mq"
        IdKind.CANDIDATE -> "dc"
        IdKind.RELEASE -> "rel"
        IdKind.AUDIT -> "audit"
        IdKind.TASK -> "task"
        IdKind.SUBTASK -> "subtask"
        IdKind.WORKSPACE -> "ws"
        IdKind.BRANCH -> "branch"
        IdKind.PULL_REQUEST -> "pr"
        IdKind.VALIDATION -> "validation"
        IdKind.CHECK -> "check"
        IdKind.FEATURE_FLAG -> "ff"
        IdKind.TRANSACTION -> "tx"
        IdKind.EVENT -> "event"
        IdKind.COMPENSATION -> "compensation"
      }

  private fun IdSequence.valueOf(kind: IdKind): Long =
      when (kind) {
        IdKind.REVIEW_REVISION -> review
        IdKind.CHANGE_REVISION -> change
        IdKind.THREAD -> thread
        IdKind.COMMENT -> comment
        IdKind.MERGE_QUEUE -> mergeQueue
        IdKind.CANDIDATE -> candidate
        IdKind.RELEASE -> release
        IdKind.AUDIT -> audit
        else -> error("sequence is not defined for $kind")
      }

  private fun IdSequence.withValue(kind: IdKind, value: Long): IdSequence =
      when (kind) {
        IdKind.REVIEW_REVISION -> copy(review = value)
        IdKind.CHANGE_REVISION -> copy(change = value)
        IdKind.THREAD -> copy(thread = value)
        IdKind.COMMENT -> copy(comment = value)
        IdKind.MERGE_QUEUE -> copy(mergeQueue = value)
        IdKind.CANDIDATE -> copy(candidate = value)
        IdKind.RELEASE -> copy(release = value)
        IdKind.AUDIT -> copy(audit = value)
        else -> error("sequence is not defined for $kind")
      }
}
