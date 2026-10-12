package io.springkit.workflow.adapter.store

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.IdKind
import io.springkit.workflow.application.IssueIdRequest
import io.springkit.workflow.application.IssueIdResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreSnapshotResponse
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreTransactionResponse
import io.springkit.workflow.application.WorkflowStorePort
import java.util.Collections
import kotlin.concurrent.thread
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class StoreIdAdapterTest :
    FunSpec({
      context("sequence 기반 식별자를 발급할 때") {
        withData(
            nameFn = { "${it.first}를 요청하면, ${it.second} 접두사의 연속된 ID를 발급합니다" },
            IdKind.REVIEW_REVISION to "rv",
            IdKind.CHANGE_REVISION to "cr",
            IdKind.THREAD to "thread",
            IdKind.COMMENT to "comment",
            IdKind.MERGE_QUEUE to "mq",
            IdKind.CANDIDATE to "dc",
            IdKind.RELEASE to "rel",
            IdKind.AUDIT to "audit",
        ) { (kind, prefix) ->
          val store = OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/state.json".toPath())
          val result = StoreIdAdapter(store).issue(IssueIdRequest(kind, count = 3))

          val success = result.shouldBeTypeOf<PortResult.Success<IssueIdResponse>>()
          success.value.ids.map { it.value } shouldBe listOf("$prefix-1", "$prefix-2", "$prefix-3")
        }
      }

      context("Store를 재시작한 뒤 sequence 기반 식별자를 발급할 때") {
        test("Store를 재시작하면, 이전에 발급한 다음 번호를 이어서 반환합니다") {
          val fileSystem = FakeFileSystem()
          val path = "/workflow/state.json".toPath()

          StoreIdAdapter(OkioWorkflowStoreAdapter(fileSystem, path))
              .issue(IssueIdRequest(IdKind.REVIEW_REVISION, count = 2))
              .shouldBeTypeOf<PortResult.Success<IssueIdResponse>>()
          val restarted = StoreIdAdapter(OkioWorkflowStoreAdapter(fileSystem, path))

          val result = restarted.issue(IssueIdRequest(IdKind.REVIEW_REVISION))

          result
              .shouldBeTypeOf<PortResult.Success<IssueIdResponse>>()
              .value
              .ids
              .single()
              .value shouldBe "rv-3"
        }
      }

      context("여러 adapter 인스턴스가 같은 Store를 사용할 때") {
        test("여러 adapter 인스턴스가 동시에 요청하면, 중복 없이 sequence를 증가시킵니다") {
          val store = OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/state.json".toPath())
          val adapters = List(8) { StoreIdAdapter(store) }
          val issued = Collections.synchronizedList(mutableListOf<String>())
          val workers = adapters.map { adapter ->
            thread(start = false) {
              val result = adapter.issue(IssueIdRequest(IdKind.COMMENT, count = 4))
              issued +=
                  result.shouldBeTypeOf<PortResult.Success<IssueIdResponse>>().value.ids.map {
                    it.value
                  }
            }
          }

          workers.forEach(Thread::start)
          workers.forEach(Thread::join)

          issued shouldHaveSize 32
          issued.shouldNotContainDuplicates()
          issued.map { it.removePrefix("comment-").toLong() }.sorted() shouldBe (1L..32L).toList()
        }
      }

      context("sequence가 없는 인프라 식별자를 발급할 때") {
        test("안전하지 않은 요청 ID를 입력하면, 접미사를 보존하면서 충돌하지 않는 ID를 발급합니다") {
          val adapter =
              StoreIdAdapter(
                  OkioWorkflowStoreAdapter(
                      FakeFileSystem(),
                      "/workflow/state.json".toPath(),
                  )
              )

          val first = adapter.issue(IssueIdRequest(IdKind.TASK, "unsafe/request value"))
          val second = adapter.issue(IssueIdRequest(IdKind.TASK, "unsafe/request value"))

          val firstId =
              first.shouldBeTypeOf<PortResult.Success<IssueIdResponse>>().value.ids.single().value
          val secondId =
              second.shouldBeTypeOf<PortResult.Success<IssueIdResponse>>().value.ids.single().value
          firstId shouldMatch Regex("[A-Za-z0-9._-]+")
          secondId shouldMatch Regex("[A-Za-z0-9._-]+")
          firstId shouldEndWith "unsafe-request-value"
          secondId shouldEndWith "unsafe-request-value"
          (firstId == secondId) shouldBe false
        }
      }

      context("Store transaction이 열리지 않을 때") {
        test("Store transaction이 열리지 않으면, fallback ID를 만들지 않고 Store 오류를 반환합니다") {
          val store =
              FailingBeginStore(
                  OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/state.json".toPath())
              )

          val result = StoreIdAdapter(store).issue(IssueIdRequest(IdKind.RELEASE))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "LOCK_UNAVAILABLE"
          store.rollbackCalled shouldBe false
        }
      }

      context("발급한 sequence를 snapshot으로 확인할 때") {
        test("sequence 발급 개수를 요청하면, 해당 카운터만 요청한 개수만큼 증가합니다") {
          val store = OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/state.json".toPath())

          StoreIdAdapter(store).issue(IssueIdRequest(IdKind.AUDIT, count = 4))
          val snapshot =
              store
                  .snapshot(StoreSnapshotRequest())
                  .shouldBeTypeOf<PortResult.Success<StoreSnapshotResponse>>()

          snapshot.value.snapshot.sequence.audit shouldBe 4
          snapshot.value.snapshot.sequence.review shouldBe 0
          snapshot.value.snapshot.sequence.change shouldBe 0
        }
      }
    })

private class FailingBeginStore(delegate: WorkflowStorePort) : WorkflowStorePort by delegate {
  var rollbackCalled: Boolean = false

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Failure(PortError("LOCK_UNAVAILABLE", "저장소가 잠겨 있습니다.", retryable = true))

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    rollbackCalled = true
    return PortResult.Failure(PortError("TRANSACTION_NOT_FOUND", "트랜잭션이 없습니다."))
  }
}
