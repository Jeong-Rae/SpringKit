package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class PostMergeCleanupUseCaseTest :
    FunSpec({
      context("MERGED parent의 정리 요청을 처리할 때") {
        test("child가 parent를 참조하면, restack 후 branch와 worktree를 정리합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED)
          val child = subTask("sk-child", requires = parent.id)
          val git = CleanupGit()
          val store =
              CleanupStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(parent, child)))

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.COMPLETED
          response.restackedSubTaskIds shouldBe listOf(child.id)
          response.removedRemoteBranches shouldBe listOf(parent.branch)
          response.removedWorkspaces shouldBe listOf("ws-${parent.id}")
          response.removedLocalBranches shouldBe listOf(parent.branch)
          git.operations.indexOf("restack:${child.id}") shouldBe 1
          git.operations shouldContain "remote:${parent.branch}"
          git.operations shouldContain "worktree:ws-${parent.id}"
          git.operations.last() shouldBe "fetch:origin/main"
          git.remoteExpectedRevisions[parent.branch] shouldBe "parent-revision"
          git.localExpectedRevisions[parent.branch] shouldBe "parent-revision"
        }

        test("요청하지 않은 다른 merged SubTask이면, branch와 worktree를 정리하지 않습니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED)
          val other = subTask("sk-other", SubTaskState.MERGED)
          val git =
              CleanupGit(
                  remoteBranches =
                      listOf(
                          RemoteBranch("origin", parent.branch, "parent-revision"),
                          RemoteBranch("origin", other.branch, "other-revision"),
                      )
              )
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot("store-1", subTasks = listOf(parent, other)),
              )

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.COMPLETED
          response.removedRemoteBranches shouldBe listOf(parent.branch)
          git.operations shouldContain "remote:${parent.branch}"
          git.operations.any { it == "remote:${other.branch}" } shouldBe false
          git.localExpectedRevisions.keys shouldBe setOf(parent.branch)
        }

        test("게시되지 않은 변경이 있는 worktree이면, 정리를 차단합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED)
          val git = CleanupGit(parentDirty = true)
          val store = CleanupStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(parent)))

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.BLOCKED
          response.blocks.map { it.code } shouldContain "WORKTREE_DIRTY"
          git.operations.any { it.startsWith("remote:") } shouldBe false
          git.operations shouldContain "fetch:origin/main"
          git.operations.any { it.startsWith("worktree:") } shouldBe false
          git.operations.any { it.startsWith("local:") } shouldBe false
        }

        test("Worktree HEAD가 원격 revision과 다르면, 게시되지 않은 commit으로 정리를 차단합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED)
          val git = CleanupGit(parentRevision = "local-revision")
          val store = CleanupStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(parent)))

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.BLOCKED
          response.blocks.map { it.code } shouldContain "UNPUSHED_COMMIT"
          git.operations.any { it.startsWith("remote:") } shouldBe false
          git.operations.any { it.startsWith("worktree:") } shouldBe false
          git.operations.any { it.startsWith("local:") } shouldBe false
        }

        test("원격 Branch가 이미 삭제되었으면, 저장된 diff identity로 로컬 정리를 재시도합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git =
              CleanupGit(
                  parentRevision = "parent-diff",
                  remoteBranches = emptyList(),
              )
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-diff")),
                  )
              )

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.COMPLETED
          response.removedRemoteBranches shouldBe emptyList()
          response.removedWorkspaces shouldBe listOf("ws-${parent.id}")
          response.removedLocalBranches shouldBe listOf(parent.branch)
          git.operations.any { it.startsWith("remote:") } shouldBe false
          git.localExpectedRevisions[parent.branch] shouldBe "parent-diff"
        }

        test("원격 Branch가 없고 기대 revision을 증명할 수 없으면, 로컬 정리를 차단합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED)
          val git = CleanupGit(remoteBranches = emptyList())
          val store = CleanupStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(parent)))

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>().data

          response.state shouldBe PostMergeCleanupState.BLOCKED
          response.blocks.map { it.code } shouldContain "EXPECTED_REVISION_UNAVAILABLE"
          git.operations.any { it.startsWith("worktree:") } shouldBe false
          git.operations.any { it.startsWith("local:") } shouldBe false
        }
      }
    })

private fun subTask(
    id: String,
    state: SubTaskState = SubTaskState.DEVELOPMENT,
    requires: String? = null,
    pullRequestId: String? = null,
) =
    SubTask(
        id = id,
        taskId = "task-1",
        title = id,
        state = state,
        workspace = Workspace("ws-$id", id, WorkspacePath("/managed/$id"), id),
        requires = requires,
        pullRequestId = pullRequestId,
    )

private fun pullRequest(id: String, diffIdentity: String) =
    PullRequest(
        id = id,
        subTaskId = "sk-parent",
        title = "parent",
        body = "body",
        base = "main",
        state = PullRequestState.MERGED,
        reviewRevision = ReviewRevision("review-$id", 1, "body"),
        changeRevision = ChangeRevision("change-$id", 1, Diff(diffIdentity)),
    )

private class CleanupStore(var current: WorkflowStoreSnapshot) : WorkflowStorePort {
  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN)
      )

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    current = request.snapshot.copy(revision = "${request.snapshot.revision}-next")
    return PortResult.Success(StoreWriteResponse(current.revision))
  }

  override fun commit(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.COMMITTED)
      )

  override fun rollback(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK)
      )

  override fun append(request: StoreEventRequest) =
      PortResult.Success(StoreEventResponse(request.event, current.revision))
}

private class CleanupGit(
    private val parentDirty: Boolean = false,
    private val parentRevision: String = "parent-revision",
    private val remoteBranches: List<RemoteBranch> =
        listOf(RemoteBranch("origin", "sk-parent", "parent-revision")),
) : GitPort {
  val operations = mutableListOf<String>()
  val remoteExpectedRevisions = mutableMapOf<String, String?>()
  val localExpectedRevisions = mutableMapOf<String, String?>()

  override fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse> {
    operations += "fetch:${request.remote}/${request.branch}"
    return PortResult.Success(MainRevisionResponse("main-revision"))
  }

  override fun inspect(request: GitInspectRequest) =
      PortResult.Success(
          GitInspectResponse(
              GitStatus(
                  revision =
                      if (request.workspaceId == "ws-sk-parent") parentRevision
                      else "${request.workspaceId}-revision",
                  fingerprint = "${request.workspaceId}-fingerprint",
                  dirty = parentDirty && request.workspaceId == "ws-sk-parent",
              )
          )
      )

  override fun createBranch(request: CreateBranchRequest) = error("not used")

  override fun createWorktree(request: CreateWorktreeRequest) = error("not used")

  override fun restack(request: RestackRequest): PortResult<RestackResponse> {
    operations += "restack:${request.workspaceId.removePrefix("ws-")}"
    return PortResult.Success(
        RestackResponse(
            revision = "${request.workspaceId}-restacked",
            fingerprint = "fingerprint",
            diffChanged = false,
            change = ChangeReceipt("restack-${request.workspaceId}", "restack"),
        )
    )
  }

  override fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse> {
    operations += "local:${request.branch}"
    localExpectedRevisions[request.branch] = request.expectedRevision
    return PortResult.Success(
        RemoveBranchResponse(
            request.branch,
            ChangeReceipt("local-${request.branch}", "remove-branch"),
        )
    )
  }

  override fun listRemoteBranches(request: ListRemoteBranchesRequest) =
      PortResult.Success(ListRemoteBranchesResponse(remoteBranches))

  override fun removeRemoteBranch(
      request: RemoveRemoteBranchRequest
  ): PortResult<RemoveRemoteBranchResponse> {
    operations += "remote:${request.branch}"
    remoteExpectedRevisions[request.branch] = request.expectedRevision
    return PortResult.Success(
        RemoveRemoteBranchResponse(
            request.remote,
            request.branch,
            ChangeReceipt("remote-${request.branch}", "remove-remote-branch"),
        )
    )
  }

  override fun removeWorktree(request: RemoveWorktreeRequest): PortResult<RemoveWorktreeResponse> {
    operations += "worktree:${request.workspaceId}"
    return PortResult.Success(
        RemoveWorktreeResponse(
            request.workspaceId,
            request.path,
            ChangeReceipt("worktree-${request.workspaceId}", "remove-worktree"),
        )
    )
  }

  override fun continueRestack(request: ContinueRestackRequest) = error("not used")

  override fun abortRestack(request: AbortRestackRequest) = error("not used")
}

private class NoopCleanupReview : ReviewPort {
  override fun open(request: OpenReviewRequest) = error("not used")

  override fun get(request: GetReviewRequest) = error("not used")

  override fun update(request: UpdateReviewRequest) = error("not used")

  override fun comment(request: AddReviewCommentRequest) = error("not used")

  override fun reply(request: ReplyReviewThreadRequest) = error("not used")

  override fun resolve(request: ResolveReviewThreadRequest) = error("not used")

  override fun ready(request: ReadyReviewRequest) = error("not used")

  override fun approve(request: ApproveReviewRequest) = error("not used")
}
