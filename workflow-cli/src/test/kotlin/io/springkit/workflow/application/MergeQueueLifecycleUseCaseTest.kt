package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskState
import io.springkit.workflow.domain.WorkflowResult

class MergeQueueLifecycleUseCaseTest :
    FunSpec({
      context("Merge Queue 검증이 통과한 SubTask를 통합하면") {
        test("SubTask를 통합하면, squash merge 결과를 저장하고 PR과 SubTask를 Merged로 전이하며 이벤트를 기록합니다") {
          val store = LifecycleStore(snapshot())
          val mergeQueue = LifecycleMergeQueuePort()
          val task = LifecycleTaskPort()

          val result = useCase(store, mergeQueue, task).merge(MergeQueueLifecycleRequest("sk-101"))

          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<MergeQueueLifecycleResponse>>().data
          response.integration.state shouldBe IntegrationState.MERGED
          response.integration.mainRevision shouldBe "main-2"
          response.subTask.state shouldBe SubTaskState.MERGED
          response.pullRequest.state shouldBe PullRequestState.MERGED
          store.written?.mergeQueue?.single()?.state shouldBe MergeQueueState.MERGED
          store.written?.eventLog?.events?.single()?.targetId shouldBe "sk-101"
          task.requests.single().state shouldBe SubTaskState.MERGED
          mergeQueue.requests.single().expectedChangeRevisionId shouldBe "cr-1"
        }

        test("이미 통합된 상태를 다시 요청하면, 원격 merge 없이 저장된 결과를 반환합니다") {
          val current =
              snapshot()
                  .copy(
                      subTasks = listOf(subTask().copy(state = SubTaskState.MERGED)),
                      pullRequests = listOf(pullRequest().copy(state = PullRequestState.MERGED)),
                      integrations =
                          listOf(
                              Integration(
                                  "sk-101",
                                  IntegrationState.MERGED,
                                  queue().copy(state = MergeQueueState.MERGED),
                                  "main-2",
                                  "squash-2",
                              )
                          ),
                      mergeQueue = listOf(queue().copy(state = MergeQueueState.MERGED)),
                  )
          val store = LifecycleStore(current)
          val mergeQueue = LifecycleMergeQueuePort()

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Success<MergeQueueLifecycleResponse>>()
          mergeQueue.requests shouldBe emptyList()
          store.written shouldBe null
        }
      }

      context("Merge Queue 검증이 통과하지 않은 상태에서 통합을 요청하면") {
        test("provider 상태가 PASSED이면, Store에 상태를 반영한 뒤 squash merge를 실행합니다") {
          val store = LifecycleStore(snapshot(queueState = MergeQueueState.VALIDATING))
          val mergeQueue = LifecycleMergeQueuePort(queue(MergeQueueState.PASSED))

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Success<MergeQueueLifecycleResponse>>()
          store.writes.first().mergeQueue.single().state shouldBe MergeQueueState.PASSED
          store.written?.mergeQueue?.single()?.state shouldBe MergeQueueState.MERGED
          mergeQueue.requests.size shouldBe 1
        }

        test("Store queue revision이 없어도 PR provider revision이 있으면 보완하고 통합합니다") {
          val legacyQueue = queue().copy(providerRevision = null)
          val store = LifecycleStore(snapshot().copy(mergeQueue = listOf(legacyQueue)))
          val mergeQueue = LifecycleMergeQueuePort(queue().copy(providerRevision = "head-1"))

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Success<MergeQueueLifecycleResponse>>()
          store.writes.first().mergeQueue.single().providerRevision shouldBe "head-1"
          mergeQueue.requests.size shouldBe 1
        }

        test("PR provider revision이 없으면 diff identity가 head처럼 보여도 통합하지 않습니다") {
          val legacyPullRequest =
              pullRequest()
                  .copy(
                      changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
                  )
          val store = LifecycleStore(snapshot().copy(pullRequests = listOf(legacyPullRequest)))
          val mergeQueue = LifecycleMergeQueuePort(queue().copy(providerRevision = "diff-1"))

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_REVISION
          mergeQueue.requests shouldBe emptyList()
          store.written shouldBe null
        }

        test("provider head가 바뀌면, queue 시점 revision을 보존하고 squash merge를 막습니다") {
          val store = LifecycleStore(snapshot(queueState = MergeQueueState.VALIDATING))
          val mergeQueue =
              LifecycleMergeQueuePort(
                  queue(MergeQueueState.PASSED).copy(providerRevision = "head-2")
              )

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))
          val retry = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_REVISION
          retry.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_REVISION
          store.writes shouldBe emptyList()
          mergeQueue.requests shouldBe emptyList()
        }

        test("provider head revision이 없으면, Store를 갱신하지 않고 통합을 차단합니다") {
          val store = LifecycleStore(snapshot())
          val mergeQueue =
              LifecycleMergeQueuePort(queue(MergeQueueState.PASSED).copy(providerRevision = null))

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_REVISION
          store.writes shouldBe emptyList()
          mergeQueue.requests shouldBe emptyList()
        }

        test("Merge Queue 검증이 통과하지 않으면, squash merge를 호출하지 않고 Merge Queue 오류를 반환합니다") {
          val store = LifecycleStore(snapshot(queueState = MergeQueueState.VALIDATING))
          val mergeQueue = LifecycleMergeQueuePort(queue(MergeQueueState.VALIDATING))

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.MERGE_QUEUE_FAILED
          mergeQueue.requests shouldBe emptyList()
          store.written shouldBe null
        }

        test("provider가 다른 통합 대상을 반환하면, 상태를 저장하지 않습니다") {
          val store = LifecycleStore(snapshot())
          val mergeQueue =
              LifecycleMergeQueuePort(
                  integration =
                      Integration(
                          "sk-other",
                          IntegrationState.MERGED,
                          queue().copy(subTaskId = "sk-other", state = MergeQueueState.MERGED),
                          "main-2",
                          "squash-2",
                      )
              )

          val result = useCase(store, mergeQueue).merge(MergeQueueLifecycleRequest("sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }
      }
    })

private fun useCase(
    store: LifecycleStore,
    mergeQueue: LifecycleMergeQueuePort,
    task: LifecycleTaskPort? = null,
) = MergeQueueLifecycleUseCase(store, mergeQueue, task)

private fun snapshot(queueState: MergeQueueState = MergeQueueState.PASSED): WorkflowStoreSnapshot {
  val task =
      Task("task-1", ExternalTaskId("TASK-1"), "기능 작업", TaskState.IN_PROGRESS, listOf("sk-101"))
  return WorkflowStoreSnapshot(
      revision = "store-1",
      tasks = listOf(task),
      subTasks = listOf(subTask()),
      pullRequests = listOf(pullRequest()),
      mergeQueue = listOf(queue(queueState)),
  )
}

private fun subTask() =
    SubTask("sk-101", "task-1", "변경 작업", SubTaskState.QUEUED, pullRequestId = "pr-1")

private fun pullRequest() =
    PullRequest(
        id = "pr-1",
        subTaskId = "sk-101",
        title = "변경 작업",
        body = "본문",
        base = "main",
        state = PullRequestState.QUEUED,
        reviewRevision = ReviewRevision("rv-1", 1, "본문"),
        changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1"), providerRevision = "head-1"),
    )

private fun queue(state: MergeQueueState = MergeQueueState.PASSED) =
    MergeQueueEntry("mq-1", "sk-101", "pr-1", "cr-1", state, providerRevision = "head-1")

private class LifecycleStore(initial: WorkflowStoreSnapshot) : WorkflowStorePort {
  var current = initial
  var written: WorkflowStoreSnapshot? = null
  val writes = mutableListOf<WorkflowStoreSnapshot>()
  private var transactions = emptySet<String>()

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    transactions += request.transactionId
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN)
    )
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    written = request.snapshot
    writes += request.snapshot
    current = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(
              request.transactionId,
              StoreTransactionState.COMMITTED,
              "store-2",
          )
      )

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK)
      )

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      PortResult.Success(StoreEventResponse(request.event, current.revision))
}

private class LifecycleMergeQueuePort(
    private val providerEntry: MergeQueueEntry = queue(),
    private val integration: Integration =
        Integration(
            "sk-101",
            IntegrationState.MERGED,
            providerEntry.copy(state = MergeQueueState.MERGED),
            "main-2",
            "squash-2",
        ),
) : MergeQueuePort {
  val requests = mutableListOf<MergeQueueMergeRequest>()

  override fun enqueue(request: EnqueueMergeRequest): PortResult<EnqueueMergeResponse> =
      error("사용하지 않는 포트 동작입니다")

  override fun get(request: GetMergeQueueRequest): PortResult<GetMergeQueueResponse> =
      PortResult.Success(GetMergeQueueResponse(listOf(providerEntry)))

  override fun merge(request: MergeQueueMergeRequest): PortResult<MergeQueueMergeResponse> {
    requests += request
    return PortResult.Success(
        MergeQueueMergeResponse(integration, ChangeReceipt("merge-1", "squash-merge"))
    )
  }
}

private class LifecycleTaskPort : TaskPort {
  val requests = mutableListOf<UpdateExternalSubTaskRequest>()

  override fun get(request: TaskLookupRequest): PortResult<TaskLookupResponse> =
      error("사용하지 않는 포트 동작입니다")

  override fun getSubTask(request: SubTaskLookupRequest): PortResult<SubTaskLookupResponse> =
      error("사용하지 않는 포트 동작입니다")

  override fun createSubTask(request: CreateSubTaskRequest): PortResult<CreateSubTaskResponse> =
      error("사용하지 않는 포트 동작입니다")

  override fun updateSubTask(
      request: UpdateExternalSubTaskRequest
  ): PortResult<UpdateExternalSubTaskResponse> {
    requests += request
    return PortResult.Success(
        UpdateExternalSubTaskResponse(
            request.externalTaskId,
            request.subTaskId,
            request.state,
            ChangeReceipt("task-1", "update-subtask"),
        )
    )
  }
}
