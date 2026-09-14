package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult

class ReviewGateUseCasesTest :
    FunSpec({
      context("Ready Gate를 기록할 때") {
        test("사람이 현재 review revision을 지정하면, PR과 SubTask를 Ready로 저장하고 감사 기록을 남깁니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.DRAFT,
                      pullRequestState = PullRequestState.DRAFT,
                  )
              )
          val review = ReviewGateReviewPort()
          val useCase = useCase(store, review)

          val result = useCase.ready(ReadyGateRequest("sk-101", "rv-1"))

          val success = result.shouldBeInstanceOf<WorkflowResult.Success<ReadyGateResponse>>()
          success.data.pullRequest.state shouldBe PullRequestState.READY
          store.written?.subTasks?.single()?.state shouldBe SubTaskState.READY
          store.written?.eventLog?.audits?.single()?.action shouldBe "ready"
          review.readyCalls shouldBe 1
        }

        test("사람이 아닌 주체가 Ready를 요청하면, 사람 결정 필요 오류로 거절하고 외부 변경을 호출하지 않습니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.DRAFT,
                      pullRequestState = PullRequestState.DRAFT,
                  )
              )
          val review = ReviewGateReviewPort()
          val identity = ReviewGateIdentityPort(Actor("agent-1", ActorKind.AGENT))
          val result = useCase(store, review, identity).ready(ReadyGateRequest("sk-101", "rv-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.HUMAN_REQUIRED
          review.readyCalls shouldBe 0
          store.beginCalls shouldBe 0
        }

        test("현재 review revision과 다른 값을 지정하면, 오래된 revision 오류로 거절합니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.DRAFT,
                      pullRequestState = PullRequestState.DRAFT,
                  )
              )
          val review = ReviewGateReviewPort()
          val result = useCase(store, review).ready(ReadyGateRequest("sk-101", "rv-old"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STALE_REVISION
          review.readyCalls shouldBe 0
          store.written shouldBe null
        }
      }

      context("Approve Gate를 기록할 때") {
        test("현재 코드와 CI를 확인하면, 승인을 기록하고 PR과 SubTask를 Queue 상태로 저장합니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.READY,
                      pullRequestState = PullRequestState.READY,
                  )
              )
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-1"))

          val success = result.shouldBeInstanceOf<WorkflowResult.Success<ApproveGateResponse>>()
          success.data.pullRequest.state shouldBe PullRequestState.QUEUED
          success.data.mergeQueue.state shouldBe MergeQueueState.QUEUED
          store.written?.subTasks?.single()?.state shouldBe SubTaskState.QUEUED
          store.written?.pullRequests?.single()?.approval?.active shouldBe true
          store.written?.eventLog?.audits?.single()?.action shouldBe "approve"
          queue.requests.single().expectedRevision shouldBe "store-1"
        }

        test("CI가 통과하지 않으면, 승인과 Queue 등록을 수행하지 않습니다") {
          val pr = pullRequest(PullRequestState.READY).copy(ci = CiStatus.FAILED)
          val store = ReviewGateStore(snapshot(pr = pr, subTaskState = SubTaskState.READY))
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.CI_NOT_PASSED
          review.approveCalls shouldBe 0
          queue.requests shouldBe emptyList()
          store.written shouldBe null
        }

        test("해결되지 않은 R thread가 있으면, 승인과 Queue 등록을 수행하지 않습니다") {
          val thread =
              ReviewThread(
                  "thread-1",
                  ReviewLevel.R,
                  listOf(
                      ReviewComment("comment-1", Actor("reviewer", ActorKind.HUMAN), "수정이 필요합니다")
                  ),
              )
          val pr =
              pullRequest(PullRequestState.READY)
                  .copy(reviewRevision = reviewRevision(threads = listOf(thread)))
          val store = ReviewGateStore(snapshot(pr = pr, subTaskState = SubTaskState.READY))
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.REQUIRED_THREAD_OPEN
          review.approveCalls shouldBe 0
          queue.requests shouldBe emptyList()
        }

        test("직접 선행 SubTask가 통합되지 않았으면, dependency 오류로 Queue 진입을 막습니다") {
          val parent = SubTask("sk-100", "task-1", "parent", state = SubTaskState.REVIEW)
          val current =
              subTask(SubTaskState.READY, PullRequestState.READY).copy(requires = parent.id)
          val store = ReviewGateStore(snapshot(subTask = current, extraSubTasks = listOf(parent)))
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.DEPENDENCY_NOT_MERGED
          review.approveCalls shouldBe 0
          queue.requests shouldBe emptyList()
        }

        test("다른 diff identity를 지정하면, 오래된 diff 오류로 거절합니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.READY,
                      pullRequestState = PullRequestState.READY,
                  )
              )
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-old"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STALE_DIFF_IDENTITY
          review.approveCalls shouldBe 0
          queue.requests shouldBe emptyList()
        }
      }

      context("Approve 이후 저장소 쓰기가 실패할 때") {
        test("외부 변경에 보상 정보가 있으면, Queue와 리뷰 승인을 역순으로 보상하고 저장소를 롤백합니다") {
          val store =
              ReviewGateStore(
                  snapshot(
                      subTaskState = SubTaskState.READY,
                      pullRequestState = PullRequestState.READY,
                  ),
                  failWrite = true,
              )
          val review = ReviewGateReviewPort()
          val queue = ReviewGateMergeQueuePort()
          val compensation = ReviewGateCompensationPort()
          val result =
              useCase(store, review, ReviewGateIdentityPort(), queue, compensation)
                  .approve(ApproveGateRequest("sk-101", "cr-1", "diff-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STATE_CONFLICT
          compensation.operations shouldBe listOf("queue-change", "approval-change")
          store.rollbackCalls shouldBe 1
        }
      }
    })

private fun useCase(
    store: ReviewGateStore,
    review: ReviewGateReviewPort,
    identity: ReviewGateIdentityPort = ReviewGateIdentityPort(),
    queue: ReviewGateMergeQueuePort? = null,
    compensation: ReviewGateCompensationPort? = null,
) =
    ReviewGateUseCases(
        identityPort = identity,
        reviewPort = review,
        storePort = store,
        mergeQueuePort = queue,
        compensationPort = compensation,
    )

private fun snapshot(
    subTaskState: SubTaskState = SubTaskState.READY,
    pullRequestState: PullRequestState = PullRequestState.READY,
    pr: PullRequest = pullRequest(pullRequestState),
    subTask: SubTask = subTask(subTaskState, pullRequestState),
    extraSubTasks: List<SubTask> = emptyList(),
) =
    WorkflowStoreSnapshot(
        revision = "store-1",
        subTasks = listOf(subTask) + extraSubTasks,
        pullRequests = listOf(pr),
    )

private fun subTask(state: SubTaskState, pullRequestState: PullRequestState) =
    SubTask(
        id = "sk-101",
        taskId = "task-1",
        title = "change",
        state = state,
        pullRequestId = "pr-1",
    )

private fun pullRequest(state: PullRequestState) =
    PullRequest(
        id = "pr-1",
        subTaskId = "sk-101",
        title = "change",
        body = "review body",
        base = "main",
        state = state,
        reviewRevision = reviewRevision(),
        changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
        ci = CiStatus.PASSED,
    )

private fun reviewRevision(threads: List<ReviewThread> = emptyList()) =
    ReviewRevision("rv-1", 1, "review body", threads = threads)

private class ReviewGateIdentityPort(
    private val current: Actor = Actor("human-1", ActorKind.HUMAN),
) : IdentityPort {
  val authorizations = mutableListOf<AuthorizeRequest>()

  override fun currentActor(request: CurrentActorRequest): PortResult<CurrentActorResponse> =
      PortResult.Success(CurrentActorResponse(current))

  override fun authorize(request: AuthorizeRequest): PortResult<AuthorizeResponse> {
    authorizations += request
    return PortResult.Success(AuthorizeResponse(allowed = true))
  }
}

private class ReviewGateReviewPort : ReviewPort {
  var readyCalls = 0
  var approveCalls = 0

  override fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse> = error("not used")

  override fun get(request: GetReviewRequest): PortResult<GetReviewResponse> = error("not used")

  override fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse> =
      error("not used")

  override fun comment(request: AddReviewCommentRequest): PortResult<AddReviewCommentResponse> =
      error("not used")

  override fun reply(request: ReplyReviewThreadRequest): PortResult<ReplyReviewThreadResponse> =
      error("not used")

  override fun resolve(
      request: ResolveReviewThreadRequest
  ): PortResult<ResolveReviewThreadResponse> = error("not used")

  override fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse> {
    readyCalls += 1
    return PortResult.Success(
        ReadyReviewResponse(
            pullRequest = pullRequest(PullRequestState.READY),
            change = receipt("ready-change"),
        ),
    )
  }

  override fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse> {
    approveCalls += 1
    val actor = request.actor
    val approval =
        Approval(
            id = "approval-1",
            actor = actor,
            changeRevisionId = request.changeRevisionId,
            diffIdentity = request.diffIdentity,
        )
    return PortResult.Success(
        ApproveReviewResponse(
            pullRequest = pullRequest(PullRequestState.APPROVED).copy(approval = approval),
            approval = approval,
            change = receipt("approval-change"),
        ),
    )
  }
}

private class ReviewGateMergeQueuePort : MergeQueuePort {
  val requests = mutableListOf<EnqueueMergeRequest>()

  override fun enqueue(request: EnqueueMergeRequest): PortResult<EnqueueMergeResponse> {
    requests += request
    return PortResult.Success(
        EnqueueMergeResponse(
            MergeQueueEntry(
                "mq-1",
                request.subTaskId,
                request.pullRequestId,
                request.changeRevisionId,
            ),
            receipt("queue-change"),
        ),
    )
  }

  override fun get(request: GetMergeQueueRequest): PortResult<GetMergeQueueResponse> =
      error("not used")

  override fun merge(request: MergeQueueMergeRequest): PortResult<MergeQueueMergeResponse> =
      error("not used")
}

private class ReviewGateCompensationPort : CompensationPort {
  val operations = mutableListOf<String>()

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    operations += request.change.id
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private class ReviewGateStore(
    private var current: WorkflowStoreSnapshot,
    private val failWrite: Boolean = false,
) : WorkflowStorePort {
  var beginCalls = 0
  var rollbackCalls = 0
  var written: WorkflowStoreSnapshot? = null

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    beginCalls += 1
    return PortResult.Success(
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.OPEN,
            current.revision,
        ),
    )
  }

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    if (failWrite) return PortResult.Failure(PortError("STATE_CONFLICT", "write failed"))
    written = request.snapshot
    current = request.snapshot.copy(revision = "store-2")
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      error("not used")

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(
              request.transactionId,
              StoreTransactionState.COMMITTED,
              "store-2",
          ),
      )

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    rollbackCalls += 1
    return PortResult.Success(
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.ROLLED_BACK,
            current.revision,
        ),
    )
  }
}

private fun receipt(id: String): ChangeReceipt =
    ChangeReceipt(
        id = id,
        operation = id,
        compensation = Compensation("comp-$id", "undo-$id", "key-$id"),
    )
