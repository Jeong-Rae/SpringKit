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
import io.springkit.workflow.domain.SubTaskCleanupState
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

          response.blocks shouldBe emptyList()
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

          response.blocks shouldBe emptyList()
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

        test("local branch 정리가 실패하면, 성공한 원격과 worktree 정리 단계를 저장하고 재시도합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit(failLocalOnce = true)
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          val first =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          first.state shouldBe PostMergeCleanupState.BLOCKED
          store.current.subTasks.single().cleanupState shouldBe SubTaskCleanupState.WORKTREE_REMOVED
          val operationsAfterFailure = git.operations.toList()

          val retry =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          retry.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations.count { it == "remote:${parent.branch}" } shouldBe 1
          git.operations.count { it == "worktree:ws-${parent.id}" } shouldBe 1
          git.operations.drop(operationsAfterFailure.size) shouldBe
              listOf("local:${parent.branch}", "fetch:origin/main")
          store.current.subTasks.single().cleanupState shouldBe SubTaskCleanupState.COMPLETED
        }

        test("worktree 삭제 뒤 상태 저장이 실패하면, 재시도에서 삭제된 worktree를 건너뜁니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit(failInspectAfterWorktreeRemoval = true)
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  ),
                  failWriteState = SubTaskCleanupState.WORKTREE_REMOVED,
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          val first =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          first.state shouldBe PostMergeCleanupState.BLOCKED
          store.current.subTasks.single().cleanupState shouldBe
              SubTaskCleanupState.REMOTE_BRANCH_REMOVED
          val retry =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          retry.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations.count { it == "worktree:ws-${parent.id}" } shouldBe 1
          git.operations.count { it == "remote:${parent.branch}" } shouldBe 1
        }

        test("원격 삭제 뒤 상태 저장이 실패하면, 재시도에서 원격 삭제를 반복하지 않습니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit()
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  ),
                  failWriteState = SubTaskCleanupState.REMOTE_BRANCH_REMOVED,
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          val first =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          first.state shouldBe PostMergeCleanupState.BLOCKED
          val retry =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          retry.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations.count { it == "remote:${parent.branch}" } shouldBe 1
        }

        test("local branch 삭제가 저장된 뒤 완료 저장이 실패하면, 재시도에서 삭제를 반복하지 않습니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit()
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  ),
                  failWriteState = SubTaskCleanupState.COMPLETED,
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          val first =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          first.state shouldBe PostMergeCleanupState.BLOCKED
          store.current.subTasks.single().cleanupState shouldBe
              SubTaskCleanupState.LOCAL_BRANCH_REMOVED
          val retry =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          retry.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations.count { it == "local:${parent.branch}" } shouldBe 1
          store.current.subTasks.single().cleanupState shouldBe SubTaskCleanupState.COMPLETED
        }

        test("main revision 갱신이 실패하면, 재시도에서 branch 삭제 없이 갱신을 다시 시도합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit(failRefreshOnce = true)
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          val first =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          first.state shouldBe PostMergeCleanupState.BLOCKED
          store.current.subTasks.single().cleanupState shouldBe
              SubTaskCleanupState.LOCAL_BRANCH_REMOVED
          val firstOperations = git.operations.toList()
          val retry =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          retry.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations.count { it == "remote:${parent.branch}" } shouldBe 1
          git.operations.count { it == "worktree:ws-${parent.id}" } shouldBe 1
          git.operations.count { it == "local:${parent.branch}" } shouldBe 1
          git.operations.drop(firstOperations.size) shouldBe listOf("fetch:origin/main")
          store.current.subTasks.single().cleanupState shouldBe SubTaskCleanupState.COMPLETED
        }

        test("완료된 정리를 다시 실행하면, 삭제 단계를 다시 호출하지 않습니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val git = CleanupGit()
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )
          val useCase = PostMergeCleanupUseCase(git, NoopCleanupReview(), store)

          useCase.execute(PostMergeCleanupRequest(parent.id))
          val operationsAfterCompletion = git.operations.toList()
          val rerun =
              useCase
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          rerun.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations shouldBe operationsAfterCompletion
        }

        test("workspace가 없는 merged SubTask이면, local branch까지 정리하고 단계를 저장합니다") {
          val parent =
              subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
                  .copy(workspace = null)
          val git = CleanupGit()
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          result.state shouldBe PostMergeCleanupState.COMPLETED
          git.operations shouldContain "remote:${parent.branch}"
          git.operations shouldContain "local:${parent.branch}"
          git.operations.any { it.startsWith("worktree:") } shouldBe false
          store.current.subTasks.single().cleanupState shouldBe SubTaskCleanupState.COMPLETED
        }

        test("main refresh 뒤 completion 대상이 없으면, 완료 저장을 차단합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )
          val git =
              CleanupGit(
                  afterRefresh = { store.current = store.current.copy(subTasks = emptyList()) }
              )

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          result.state shouldBe PostMergeCleanupState.BLOCKED
          result.blocks.single().code shouldBe "SUBTASK_NOT_FOUND"
        }

        test("main refresh 뒤 completion 상태가 다르면, 상태 충돌로 완료를 차단합니다") {
          val parent = subTask("sk-parent", SubTaskState.MERGED, pullRequestId = "pr-parent")
          val store =
              CleanupStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent),
                      pullRequests = listOf(pullRequest("pr-parent", "parent-revision")),
                  )
              )
          val git =
              CleanupGit(
                  afterRefresh = {
                    store.current =
                        store.current.copy(
                            subTasks =
                                store.current.subTasks.map {
                                  it.copy(cleanupState = SubTaskCleanupState.WORKTREE_REMOVED)
                                }
                        )
                  }
              )

          val result =
              PostMergeCleanupUseCase(git, NoopCleanupReview(), store)
                  .execute(PostMergeCleanupRequest(parent.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<PostMergeCleanupResponse>>()
                  .data

          result.state shouldBe PostMergeCleanupState.BLOCKED
          result.blocks.single().code shouldBe "STATE_CONFLICT"
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

private class CleanupStore(
    var current: WorkflowStoreSnapshot,
    private var failWriteState: SubTaskCleanupState? = null,
) : WorkflowStorePort {
  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN)
      )

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    val newState = request.snapshot.subTasks.singleOrNull()?.cleanupState
    if (failWriteState != null && newState == failWriteState) {
      failWriteState = null
      return PortResult.Failure(PortError("STORE_DOWN", "store write failed"))
    }
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
    remoteBranches: List<RemoteBranch> =
        listOf(RemoteBranch("origin", "sk-parent", "parent-revision")),
    private var failLocalOnce: Boolean = false,
    private var failInspectAfterWorktreeRemoval: Boolean = false,
    private var failRefreshOnce: Boolean = false,
    private val afterRefresh: (() -> Unit)? = null,
) : GitPort {
  val operations = mutableListOf<String>()
  val remoteExpectedRevisions = mutableMapOf<String, String?>()
  val localExpectedRevisions = mutableMapOf<String, String?>()
  private var remoteBranches = remoteBranches.toMutableList()
  private val removedWorktrees = mutableSetOf<String>()

  override fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse> {
    operations += "fetch:${request.remote}/${request.branch}"
    if (failRefreshOnce) {
      failRefreshOnce = false
      return PortResult.Failure(PortError("MAIN_REVISION_REFRESH_FAILED", "fetch failed"))
    }
    afterRefresh?.invoke()
    return PortResult.Success(MainRevisionResponse("main-revision"))
  }

  override fun inspect(request: GitInspectRequest): PortResult<GitInspectResponse> {
    if (failInspectAfterWorktreeRemoval && request.workspaceId in removedWorktrees) {
      return PortResult.Failure(PortError("WORKSPACE_NOT_FOUND", "workspace is missing"))
    }
    return PortResult.Success(
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
  }

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
    if (failLocalOnce) {
      failLocalOnce = false
      return PortResult.Failure(PortError("LOCAL_BRANCH_BUSY", "local branch is busy"))
    }
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
    remoteBranches.removeAll { it.remote == request.remote && it.branch == request.branch }
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
    removedWorktrees += request.workspaceId
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
