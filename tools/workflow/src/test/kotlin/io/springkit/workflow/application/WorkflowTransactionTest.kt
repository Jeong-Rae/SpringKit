package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.WorkflowResult

class WorkflowTransactionTest :
    FunSpec({
      context("트랜잭션 작업이 성공하는 상황에서") {
        test("작업을 실행하면, 한 번 쓰고 커밋합니다") {
          val store = TransactionRecordingStore()
          val transaction = WorkflowTransaction(store, RecordingCompensation())

          val result =
              transaction.execute(StoreTransactionRequest("tx-1")) { snapshot ->
                PortResult.Success(TransactionMutation("done", snapshot))
              }

          result.shouldBeInstanceOf<WorkflowResult.Success<String>>().data shouldBe "done"
          store.calls shouldBe listOf("begin", "snapshot", "write", "commit")
        }
      }

      context("트랜잭션 쓰기가 실패하는 상황에서") {
        test("여러 변경을 기록하면, 역순으로 보상하고 롤백합니다") {
          val store = TransactionRecordingStore(failWrite = true)
          val compensations = RecordingCompensation()
          val transaction = WorkflowTransaction(store, compensations)
          val first = change("first")
          val second = change("second")

          val result =
              transaction.execute(StoreTransactionRequest("tx-1")) { snapshot ->
                PortResult.Success(TransactionMutation("unused", snapshot, listOf(first, second)))
              }

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STATE_CONFLICT
          compensations.operations shouldBe listOf("second", "first")
          store.calls shouldBe listOf("begin", "snapshot", "write", "rollback")
        }
      }

      context("포트 작업이 실패하는 상황에서") {
        test("안정적인 실패 코드를 반환하면, 같은 코드로 워크플로 실패를 반환합니다") {
          val store = TransactionRecordingStore()
          val transaction = WorkflowTransaction(store, RecordingCompensation())

          val result =
              transaction.execute<String>(StoreTransactionRequest("tx-1")) {
                PortResult.Failure(PortError("IDEMPOTENCY_CONFLICT", "request differs"))
              }

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.IDEMPOTENCY_CONFLICT
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }
      }
    })

private fun change(operation: String) =
    ChangeReceipt(
        id = "change-$operation",
        operation = operation,
        compensation = Compensation("comp-$operation", operation, operation),
    )

private class TransactionRecordingStore(private val failWrite: Boolean = false) :
    WorkflowStorePort {
  val calls = mutableListOf<String>()
  private val snapshot = WorkflowStoreSnapshot("store-1")

  override fun begin(request: StoreTransactionRequest) = success("begin")

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> {
    calls += "snapshot"
    return PortResult.Success(StoreSnapshotResponse(snapshot))
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    calls += "write"
    return if (failWrite) {
      PortResult.Failure(PortError("STATE_CONFLICT", "stale store revision"))
    } else {
      PortResult.Success(StoreWriteResponse("store-2"))
    }
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      error("not used by this test")

  override fun commit(request: StoreTransactionRequest) = success("commit")

  override fun rollback(request: StoreTransactionRequest) = success("rollback")

  private fun success(call: String): PortResult<StoreTransactionResponse> {
    calls += call
    return PortResult.Success(StoreTransactionResponse("tx-1", StoreTransactionState.OPEN))
  }
}

private class RecordingCompensation : CompensationPort {
  val operations = mutableListOf<String>()

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    operations += request.change.operation
    return PortResult.Success(CompensateResponse(request.change))
  }
}
