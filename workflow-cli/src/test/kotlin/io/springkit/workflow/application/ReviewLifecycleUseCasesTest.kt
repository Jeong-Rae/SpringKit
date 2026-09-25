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
import io.springkit.workflow.domain.Exposure
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
                          body = validReviewBody(),
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
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
          fixture.publish.requests.single().commitTitle shouldBe "[sk-101] 변경 설명"
          fixture.review.openRequests.single().base shouldBe "main"
          fixture.ci.starts shouldBe 1
          fixture.ai.starts shouldBe 1
          fixture.store.current.pullRequests.single().id shouldBe "pr-101"
          fixture.store.current.eventLog.events.single().shouldBeInstanceOf<ReviewOpened>()
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
                          body = validReviewBody(),
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_DIFF_IDENTITY
          fixture.publish.requests shouldBe emptyList()
          fixture.store.current.pullRequests shouldBe emptyList()
        }

        test("Feature Flag 기본 동작이 안전하지 않으면, PR을 게시하지 않고 Gate 오류를 반환합니다") {
          val fixture = ReviewLifecycleFixture(featureSafeDefault = false)

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = validReviewBody(),
                          risk = Risk.HIGH,
                          exposure = Exposure.FEATURE_FLAG,
                          featureFlagId = "flag-v2",
                      )
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.INVALID_GATE_STATE
          fixture.publish.requests shouldBe emptyList()
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
                          body = validReviewBody(),
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
                      )
                  )

          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<OpenReviewLifecycleResponse>>().data
          response.pullRequest.aiReview shouldBe AiReviewStatus.PENDING
          fixture.ai.starts shouldBe 0
          fixture.store.current.pullRequests.single().aiReview shouldBe AiReviewStatus.PENDING
        }
      }

      context("PR 본문 템플릿을 검증하는 상황에서") {
        test("필수 제목이 없으면, 포트를 호출하지 않고 본문 오류를 SubTask 대상으로 반환합니다") {
          val fixture = ReviewLifecycleFixture()

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = "# 해결하려는 문제\n내용",
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
                      )
                  )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.INVALID_ARGUMENT
          failure.message shouldBe "PR 본문에 '왜 지금 해결해야 하는가' 제목이 없습니다."
          failure.blockedBy.single().target shouldBe "sk-101"
          fixture.git.inspectCalls shouldBe 0
          fixture.publish.requests shouldBe emptyList()
          fixture.review.getCalls shouldBe 0
          fixture.review.openRequests shouldBe emptyList()
          fixture.store.snapshotCalls shouldBe 0
          fixture.store.beginCalls shouldBe 0
        }

        test("필수 제목의 순서가 잘못되면, 포트를 호출하지 않고 제목 순서 오류를 반환합니다") {
          val fixture = ReviewLifecycleFixture()
          val body =
              validReviewBody()
                  .replace("## 해결하려는 문제", "## 임시 제목")
                  .replace("## 왜 지금 해결해야 하는가", "## 해결하려는 문제")
                  .replace("## 임시 제목", "## 왜 지금 해결해야 하는가")

          val result =
              fixture
                  .useCases()
                  .open(
                      OpenReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          body = body,
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
                      )
                  )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.INVALID_ARGUMENT
          failure.message shouldBe
              "PR 본문 제목 순서가 잘못되었습니다. '해결하려는 문제' 다음에 '왜 지금 해결해야 하는가' 제목이 있어야 합니다."
          failure.blockedBy.single().target shouldBe "sk-101"
          fixture.git.inspectCalls shouldBe 0
          fixture.review.openRequests shouldBe emptyList()
          fixture.store.snapshotCalls shouldBe 0
        }

        test("본문 갱신의 제목이 누락되면, Review 조회 전에 PR 대상으로 오류를 반환합니다") {
          val fixture = ReviewLifecycleFixture(withPullRequest = true)

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                          body = "갱신된 본문",
                      )
                  )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.INVALID_ARGUMENT
          failure.message shouldBe "PR 본문에 '해결하려는 문제' 제목이 없습니다."
          failure.blockedBy.single().target shouldBe "pr-101"
          fixture.git.inspectCalls shouldBe 0
          fixture.review.getCalls shouldBe 0
          fixture.review.updateCalls shouldBe 0
          fixture.store.snapshotCalls shouldBe 0
          fixture.store.beginCalls shouldBe 0
        }
      }

      context("Review를 갱신하는 상황에서") {
        test("본문만 바뀌면, change revision과 승인과 게이트를 유지하고 review revision만 갱신합니다") {
          val fixture = ReviewLifecycleFixture(withPullRequest = true)

          val result =
              fixture
                  .useCases()
                  .update(
                      UpdateReviewLifecycleRequest(
                          workspaceId = "ws-101",
                          subTaskId = "sk-101",
                          pullRequestId = "pr-101",
                          expectedReviewRevisionId = "rv-1",
                          body = validReviewBody(),
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
          fixture.publish.requests.single().commitTitle shouldBe "[sk-101] 변경 설명"
          fixture.ci.starts shouldBe 1
          fixture.ai.starts shouldBe 1
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
                          body = validReviewBody(),
                          risk = Risk.NORMAL,
                          exposure = Exposure.UNCHANGED,
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
    private val ciFailure: Boolean = false,
    private val featureSafeDefault: Boolean = true,
    private val withAiReview: Boolean = true,
) {
  val store = LifecycleFakeStore(snapshot(withPullRequest))
  val workspace = LifecycleFakeWorkspacePort()
  val git = LifecycleFakeGitPort(statusFingerprint, statusRevision)
  val publish = LifecycleFakeGitPublishPort()
  val review = LifecycleFakeReviewPort()
  val ci = LifecycleFakeCiPort(ciFailure)
  val ai = LifecycleFakeAiPort()
  val compensation = LifecycleFakeCompensationPort()
  val featureFlag = LifecycleFakeFeatureFlagPort(featureSafeDefault)

  init {
    if (withPullRequest) store.current = store.current.copy(pullRequests = listOf(pullRequest()))
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
          featureFlagPort = featureFlag,
      )
}

private fun testWorkspace(): Workspace =
    Workspace("ws-101", "sk-101", WorkspacePath("workspace/sk-101"), "sk-101")

private fun validReviewBody(): String =
    listOf(
            "해결하려는 문제",
            "왜 지금 해결해야 하는가",
            "어떻게 해결했는가",
            "한계와 트레이드오프",
            "기존 기능에 미치는 영향",
            "Edge Case와 실패 시나리오",
            "검토한 대안과 선택 이유",
            "리뷰 포인트",
        )
        .joinToString("\n\n") { heading -> "## $heading\n설명" }

private fun snapshot(withPullRequest: Boolean): WorkflowStoreSnapshot =
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
                    "변경 설명",
                    state = if (withPullRequest) SubTaskState.DRAFT else SubTaskState.DEVELOPMENT,
                    workspace = testWorkspace(),
                    pullRequestId = if (withPullRequest) "pr-101" else null,
                )
            ),
        workspaces = listOf(testWorkspace()),
        checks = mapOf("sk-101" to check("fingerprint-1", "head-1")),
        pullRequests = if (withPullRequest) listOf(pullRequest()) else emptyList(),
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

private fun pullRequest(): PullRequest =
    PullRequest(
        id = "pr-101",
        subTaskId = "sk-101",
        title = "[sk-101] 변경 설명",
        body = validReviewBody(),
        base = "main",
        state = PullRequestState.DRAFT,
        reviewRevision = reviewRevision("rv-1", validReviewBody()),
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

private class LifecycleFakeReviewPort : ReviewPort {
  val openRequests = mutableListOf<OpenReviewRequest>()
  var getCalls = 0
  var updateCalls = 0
  private var current = pullRequest()

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
            request.exposure,
            request.featureFlagId,
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

private class LifecycleFakeFeatureFlagPort(private val safeDefault: Boolean) : FeatureFlagPort {
  override fun validateDefault(
      request: ValidateFeatureFlagRequest
  ): PortResult<ValidateFeatureFlagResponse> =
      PortResult.Success(ValidateFeatureFlagResponse(safeDefault))
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
