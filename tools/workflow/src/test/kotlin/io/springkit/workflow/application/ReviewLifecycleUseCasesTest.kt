package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.CiRun
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewOpened
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class ReviewLifecycleUseCasesTest :
    FunSpec({
      context("Review를 처음 게시하는 상황에서") {
        test("현재 fingerprint의 모든 check가 PASSED이면, 제목과 base를 만들고 Draft PR과 게이트를 저장합니다") {
          val fixture = ReviewLifecycleFixture()

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = sampleReviewBody(),
                          risk = Risk.NORMAL,
                      )
                  )

          val response =
              result
                  .shouldBeInstanceOf<WorkflowResult.Success<OpenReviewLifecycleResponse>>()
                  .data
                  .shouldBeInstanceOf<OpenReviewLifecycleResponse>()
          response.pullRequest.title shouldBe "[sk-101] 변경 설명"
          response.pullRequest.state shouldBe PullRequestState.DRAFT
          response.pullRequest.changeRevision.diff.identity shouldBe "published-fingerprint"
          fixture.publish.requests.single().commitTitle shouldBe "Fix: 변경 설명"
          fixture.review.openRequests.single().base shouldBe "main"
          fixture.ci.starts shouldBe 1
          fixture.ai.starts shouldBe 1
          fixture.store.current.pullRequests.single().id shouldBe "pr-101"
          fixture.store.current.eventLog.events.single().shouldBeInstanceOf<ReviewOpened>()
        }

        test("Type 접두사 뒤 설명이 비어 있으면, transaction과 외부 게시 전에 요청을 거부합니다") {
          val fixture = ReviewLifecycleFixture(subTaskTitle = "Fix:   ")

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = sampleReviewBody(),
                          risk = Risk.NORMAL,
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_ARGUMENT
          fixture.publish.requests shouldBe emptyList()
          fixture.review.openRequests shouldBe emptyList()
          fixture.store.beginCalls shouldBe 0
          fixture.ci.starts shouldBe 0
          fixture.ai.starts shouldBe 0
        }

        test("현재 fingerprint와 check가 일치하지 않으면, 외부 게시 없이 CHECK 오류를 반환합니다") {
          val fixture = ReviewLifecycleFixture(statusFingerprint = "changed-fingerprint")

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = sampleReviewBody(),
                          risk = Risk.NORMAL,
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_DIFF_IDENTITY
          fixture.publish.requests shouldBe emptyList()
          fixture.store.current.pullRequests shouldBe emptyList()
        }

        test("AI Review 공급자가 없으면, Draft PR을 열고 AI Review를 PENDING으로 유지합니다") {
          val fixture = ReviewLifecycleFixture(withAiReview = false)

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = sampleReviewBody(),
                          risk = Risk.NORMAL,
                      )
                  )

          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<OpenReviewLifecycleResponse>>().data
          response.pullRequest.aiReview shouldBe AiReviewStatus.PENDING
          fixture.ai.starts shouldBe 0
          fixture.store.current.pullRequests.single().aiReview shouldBe AiReviewStatus.PENDING
        }
      }

      context("PR 본문 원문을 전달하는 상황에서") {
        test("초기 게시 본문에 임의의 제목과 슬롯이 있어도, 본문 원문을 그대로 전달합니다") {
          val fixture = ReviewLifecycleFixture()
          val body = "  임의 제목\n\n{{placeholder}}\n본문 끝  "

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = body,
                          risk = Risk.NORMAL,
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Success<OpenReviewLifecycleResponse>>()
          fixture.review.openRequests.single().body shouldBe body
        }

        test("본문 갱신에 Markdown과 슬롯이 있어도, 본문 원문을 그대로 전달합니다") {
          val fixture = ReviewLifecycleFixture(withPullRequest = true)
          val body = "\n# 사용자 제목\n\n`{{placeholder}}`\n  "

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                          body = body,
                      )
                  )

          val response =
              result
                  .shouldBeInstanceOf<WorkflowResult.Success<UpdateReviewLifecycleResponse>>()
                  .data
          response.pullRequest.body shouldBe body
        }
      }

      context("Review를 갱신하는 상황에서") {
        test("본문만 바뀌면, change revision과 승인과 게이트를 유지하고 review revision만 갱신합니다") {
          val fixture = ReviewLifecycleFixture(withPullRequest = true, subTaskTitle = "Fix:")

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                          body = sampleReviewBody(),
                      )
                  )

          val response =
              result
                  .shouldBeInstanceOf<WorkflowResult.Success<UpdateReviewLifecycleResponse>>()
                  .data
                  .shouldBeInstanceOf<UpdateReviewLifecycleResponse>()
          response.codeChanged shouldBe false
          response.pullRequest.reviewRevision.id shouldBe "rv-2"
          response.pullRequest.changeRevision.id shouldBe "cr-1"
          response.pullRequest.approval?.active shouldBe true
          response.pullRequest.ci shouldBe CiStatus.PASSED
          response.pullRequest.aiReview shouldBe AiReviewStatus.PASSED
          fixture.publish.requests shouldBe emptyList()
          fixture.ci.starts shouldBe 0
          fixture.ai.starts shouldBe 0
        }

        test("fingerprint가 바뀌면, PASSED check 이후 새 change revision과 무효화된 게이트를 게시합니다") {
          val fixture =
              ReviewLifecycleFixture(
                  withPullRequest = true,
                  statusFingerprint = "fingerprint-2",
                  statusRevision = "head-2",
                  pullRequestTitle = "사용자가 수정한 PR 제목",
              )
          fixture.store.current =
              fixture.store.current.copy(
                  checks = mapOf("sk-101" to check("fingerprint-2", "head-2"))
              )

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                      )
                  )

          val response =
              result
                  .shouldBeInstanceOf<WorkflowResult.Success<UpdateReviewLifecycleResponse>>()
                  .data
                  .shouldBeInstanceOf<UpdateReviewLifecycleResponse>()
          response.codeChanged shouldBe true
          response.pullRequest.reviewRevision.id shouldBe "rv-2"
          response.pullRequest.changeRevision.id shouldBe "cr-2"
          response.pullRequest.changeRevision.diff.identity shouldBe "published-fingerprint"
          response.pullRequest.approval shouldBe null
          fixture.publish.requests.single().commitTitle shouldBe "Fix: 변경 설명"
          fixture.ci.starts shouldBe 1
          fixture.ai.starts shouldBe 1
        }

        test("Type 접두사 뒤 설명이 비어 있으면, 코드 변경 게시 전에 요청을 거부합니다") {
          val fixture =
              ReviewLifecycleFixture(
                  withPullRequest = true,
                  subTaskTitle = "Fix:",
                  statusFingerprint = "fingerprint-2",
                  statusRevision = "head-2",
              )
          fixture.store.current =
              fixture.store.current.copy(
                  checks = mapOf("sk-101" to check("fingerprint-2", "head-2"))
              )

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_ARGUMENT
          fixture.publish.requests shouldBe emptyList()
          fixture.review.updateCalls shouldBe 0
          fixture.store.beginCalls shouldBe 0
          fixture.ci.starts shouldBe 0
          fixture.ai.starts shouldBe 0
        }

        test("CI 시작이 실패하면, Store를 커밋하지 않고 외부 변경 보상을 요청합니다") {
          val fixture = ReviewLifecycleFixture(ciFailure = true)

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = sampleReviewBody(),
                          risk = Risk.NORMAL,
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.EXTERNAL_FAILURE
          fixture.store.rollbackCalls shouldBe 1
          fixture.store.current.pullRequests shouldBe emptyList()
          fixture.compensation.calls shouldBe 2
        }
      }
    })

private class ReviewLifecycleFixture(
    statusFingerprint: String = "fingerprint-1",
    statusRevision: String = "head-1",
    withPullRequest: Boolean = false,
    private val subTaskTitle: String = "Fix: 변경 설명",
    private val pullRequestTitle: String = "[sk-101] 변경 설명",
    private val ciFailure: Boolean = false,
    private val withAiReview: Boolean = true,
) {
  val store = LifecycleFakeStore(snapshot(withPullRequest, subTaskTitle, pullRequestTitle))
  val workspace = LifecycleFakeWorkspacePort()
  val git = LifecycleFakeGitPort(statusFingerprint, statusRevision)
  val publish = LifecycleFakeGitPublishPort()
  val review = LifecycleFakeReviewPort(pullRequest(pullRequestTitle))
  val ci = LifecycleFakeCiPort(ciFailure)
  val ai = LifecycleFakeAiPort()
  val compensation = LifecycleFakeCompensationPort()

  init {
    if (withPullRequest) {
      store.current = store.current.copy(pullRequests = listOf(pullRequest(pullRequestTitle)))
    }
  }

  fun useCases() =
      ReviewLifecycleUseCases(
          workspacePort = workspace,
          gitPort = git,
          gitPublishPort = publish,
          reviewPort = review,
          ciPort = ci,
          aiReviewPort = ai.takeIf { withAiReview },
          storePort = store,
          compensationPort = compensation,
      )
}

private fun testWorkspace(): Workspace =
    Workspace("ws-101", "sk-101", WorkspacePath("workspace/sk-101"), "sk-101")

private fun sampleReviewBody(): String = "본문입니다."

private fun snapshot(
    withPullRequest: Boolean,
    subTaskTitle: String,
    pullRequestTitle: String,
): WorkflowStoreSnapshot =
    WorkflowStoreSnapshot(
        revision = "0",
        sequence =
            io.springkit.workflow.domain.IdSequence(
                review = if (withPullRequest) 1 else 0,
                change = if (withPullRequest) 1 else 0,
            ),
        tasks =
            listOf(
                io.springkit.workflow.domain.Task(
                    "task-101",
                    ExternalTaskId("TASK-101"),
                    "작업",
                    subTaskIds = listOf("sk-101"),
                )
            ),
        subTasks =
            listOf(
                SubTask(
                    "sk-101",
                    "task-101",
                    subTaskTitle,
                    state = if (withPullRequest) SubTaskState.DRAFT else SubTaskState.DEVELOPMENT,
                    workspace = testWorkspace(),
                    pullRequestId = if (withPullRequest) "pr-101" else null,
                )
            ),
        workspaces = listOf(testWorkspace()),
        checks = mapOf("sk-101" to check("fingerprint-1", "head-1")),
        pullRequests = if (withPullRequest) listOf(pullRequest(pullRequestTitle)) else emptyList(),
    )

private fun check(fingerprint: String, revision: String): CheckSummary =
    CheckSummary(
        fingerprint = fingerprint,
        revision = revision,
        checks =
            listOf(
                CheckResult("check-test", "test", ValidationStatus.PASSED, fingerprint, revision),
                CheckResult("check-build", "build", ValidationStatus.PASSED, fingerprint, revision),
            ),
    )

private fun reviewRevision(id: String, body: String): ReviewRevision =
    ReviewRevision(id, id.removePrefix("rv-").toLong(), body)

private fun pullRequest(title: String = "[sk-101] 변경 설명"): PullRequest =
    PullRequest(
        id = "pr-101",
        subTaskId = "sk-101",
        title = title,
        body = sampleReviewBody(),
        base = "main",
        state = PullRequestState.DRAFT,
        reviewRevision = reviewRevision("rv-1", sampleReviewBody()),
        changeRevision = ChangeRevision("cr-1", 1, Diff("fingerprint-1")),
        approval =
            Approval("approval-1", Actor("human-1", ActorKind.HUMAN), "cr-1", "fingerprint-1"),
        ci = CiStatus.PASSED,
        aiReview = AiReviewStatus.PASSED,
    )

private class LifecycleFakeWorkspacePort : WorkspacePort {
  override fun get(request: WorkspaceLookupRequest): PortResult<WorkspaceLookupResponse> =
      PortResult.Success(
          WorkspaceLookupResponse(
              Workspace("ws-101", "sk-101", WorkspacePath("workspace/sk-101"), "sk-101")
          )
      )

  override fun create(request: CreateWorkspaceRequest): PortResult<CreateWorkspaceResponse> =
      unsupported()

  override fun delete(request: DeleteWorkspaceRequest): PortResult<DeleteWorkspaceResponse> =
      unsupported()
}

private class LifecycleFakeGitPort(private val fingerprint: String, private val revision: String) :
    GitPort {
  var inspectCalls = 0

  override fun inspect(request: GitInspectRequest): PortResult<GitInspectResponse> =
      PortResult.Success(GitInspectResponse(GitStatus(revision, fingerprint, dirty = true))).also {
        inspectCalls += 1
      }

  override fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse> =
      unsupported()

  override fun createBranch(request: CreateBranchRequest): PortResult<CreateBranchResponse> =
      unsupported()

  override fun createWorktree(request: CreateWorktreeRequest): PortResult<CreateWorktreeResponse> =
      unsupported()

  override fun restack(request: RestackRequest): PortResult<RestackResponse> = unsupported()

  override fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse> =
      unsupported()
}

private class LifecycleFakeGitPublishPort : GitPublishPort {
  val requests = mutableListOf<PublishBranchRequest>()

  override fun publish(request: PublishBranchRequest): PortResult<PublishBranchResponse> {
    requests += request
    return PortResult.Success(
        PublishBranchResponse(
            request.branch,
            "commit-2",
            "published-fingerprint",
            true,
            receipt("publish"),
        )
    )
  }
}

private class LifecycleFakeReviewPort(initialPullRequest: PullRequest) : ReviewPort {
  val openRequests = mutableListOf<OpenReviewRequest>()
  var getCalls = 0
  var updateCalls = 0
  private var current = initialPullRequest

  override fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse> {
    openRequests += request
    current =
        PullRequest(
            request.subTaskId.let { "pr-101" },
            request.subTaskId,
            request.title,
            request.body,
            request.base,
            PullRequestState.DRAFT,
            request.risk,
            request.reviewRevision,
            request.changeRevision,
        )
    return PortResult.Success(OpenReviewResponse(current, receipt("open")))
  }

  override fun get(request: GetReviewRequest): PortResult<GetReviewResponse> =
      PortResult.Success(GetReviewResponse(current)).also { getCalls += 1 }

  override fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse> {
    updateCalls += 1
    current =
        current.copy(
            body = request.body ?: current.body,
            reviewRevision = request.reviewRevision ?: current.reviewRevision,
            changeRevision = request.changeRevision ?: current.changeRevision,
        )
    return PortResult.Success(
        UpdateReviewResponse(current, request.changeRevision != null, receipt("update"))
    )
  }

  override fun comment(request: AddReviewCommentRequest): PortResult<AddReviewCommentResponse> =
      unsupported()

  override fun reply(request: ReplyReviewThreadRequest): PortResult<ReplyReviewThreadResponse> =
      unsupported()

  override fun resolve(
      request: ResolveReviewThreadRequest
  ): PortResult<ResolveReviewThreadResponse> = unsupported()

  override fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse> = unsupported()

  override fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse> =
      unsupported()
}

private class LifecycleFakeCiPort(private val fail: Boolean) : CiPort {
  var starts = 0

  override fun start(request: StartCiRequest): PortResult<StartCiResponse> {
    starts += 1
    if (fail) return PortResult.Failure(PortError("CI_START_FAILED", "CI 시작 실패"))
    return PortResult.Success(
        StartCiResponse(CiRun("ci-1", CiStatus.RUNNING, revision = request.revision), receipt("ci"))
    )
  }

  override fun get(request: GetCiRequest): PortResult<GetCiResponse> = unsupported()
}

private class LifecycleFakeAiPort : AiReviewPort {
  var starts = 0

  override fun start(request: StartAiReviewRequest): PortResult<StartAiReviewResponse> {
    starts += 1
    return PortResult.Success(StartAiReviewResponse(AiReviewStatus.RUNNING, receipt("ai")))
  }

  override fun get(request: GetAiReviewRequest): PortResult<GetAiReviewResponse> = unsupported()
}

private class LifecycleFakeCompensationPort : CompensationPort {
  var calls = 0

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    calls += 1
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private class LifecycleFakeStore(initial: WorkflowStoreSnapshot) : WorkflowStorePort {
  var current = initial
  var rollbackCalls = 0
  var snapshotCalls = 0
  var beginCalls = 0
  private var pending: WorkflowStoreSnapshot? = null

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> =
      PortResult.Success(StoreSnapshotResponse(current)).also { snapshotCalls += 1 }

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    beginCalls += 1
    return PortResult.Success(
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.OPEN,
            current.revision,
        )
    )
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    pending = request.snapshot.copy(revision = (current.revision.toLong() + 1).toString())
    return PortResult.Success(StoreWriteResponse(checkNotNull(pending).revision))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> {
    val base = pending ?: current
    pending = base.copy(eventLog = base.eventLog.append(request.event, request.audit))
    return PortResult.Success(StoreEventResponse(request.event, checkNotNull(pending).revision))
  }

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    current = pending ?: current
    pending = null
    return PortResult.Success(
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.COMMITTED,
            current.revision,
        )
    )
  }

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    rollbackCalls += 1
    pending = null
    return PortResult.Success(
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.ROLLED_BACK,
            current.revision,
        )
    )
  }
}

private fun receipt(operation: String): ChangeReceipt =
    ChangeReceipt("change-$operation", operation)

private fun <T> unsupported(): PortResult<T> =
    PortResult.Failure(PortError("UNSUPPORTED", "테스트에서 지원하지 않는 동작입니다."))
