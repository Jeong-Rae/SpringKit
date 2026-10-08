package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.SyncConflict
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class StackSyncUseCasesTest :
    FunSpec({
      context("상위 작업에 쌓는 상황에서") {
        test("직접 의존성을 지정하면, 상위 작업 리비전을 기준으로 기록합니다") {
          val parent = subTask("sk-parent")
          val child = subTask("sk-child")
          val store =
              RecordingStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(parent, child)))
          val git = RecordingGit()
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val result = useCase.stack(StackRequest(child.id, requires = parent.id))

          val response = result.shouldBeInstanceOf<WorkflowResult.Success<StackResponse>>().data
          response.baseBranch shouldBe parent.branch
          response.baseRevision shouldBe "parent-revision"
          response.subTask.requires shouldBe parent.id
          git.lastRestack?.baseBranch shouldBe parent.branch
          git.lastRestack?.baseRevision shouldBe "parent-revision"
          store.written?.dependencies shouldBe listOf(Dependency(child.id, parent.id))
        }

        test("의존성 선택지를 모두 생략하거나 함께 지정하면, 잘못된 인자로 거부합니다") {
          val child = subTask("sk-child")
          val store = RecordingStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(child)))
          val useCase = StackSyncUseCases(RecordingGit(), NoopReviewPort(), store)

          val missing = useCase.stack(StackRequest(child.id))
          val both = useCase.stack(StackRequest(child.id, requires = "sk-parent", clear = true))

          missing.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_ARGUMENT
          both.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_ARGUMENT
        }
      }

      context("동기화하는 상황에서") {
        test("병합된 상위 작업의 의존성을 동기화하면, 기준을 main으로 되돌립니다") {
          val parent = subTask("sk-parent", state = SubTaskState.MERGED)
          val child = subTask("sk-child", requires = parent.id)
          val store =
              RecordingStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(parent, child),
                      dependencies = listOf(Dependency(child.id, parent.id)),
                  ),
              )
          val git = RecordingGit()
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val result = useCase.sync(SyncRequest(child.id))

          val response = result.shouldBeInstanceOf<WorkflowResult.Success<SyncResponse>>().data
          response.baseBranch shouldBe "main"
          response.baseRevision shouldBe "main-revision"
          response.subTask.requires shouldBe null
          store.written?.subTasks?.single { it.id == child.id }?.requires shouldBe null
          store.written?.dependencies.orEmpty() shouldBe emptyList()
          git.lastRestack?.baseBranch shouldBe "main"
        }

        test("동기화 충돌 상태에서 프로세스를 재시작하면, 저장된 복구 상태로 두 번째 자동 동기화를 막습니다") {
          val child = subTask("sk-child")
          val store = RecordingStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(child)))
          val git =
              RecordingGit(
                  RestackResponse(
                      revision = "child-revision",
                      fingerprint = "child-fingerprint",
                      diffChanged = false,
                      conflicts = listOf("src/main.kt"),
                      change = ChangeReceipt("restack-1", "restack"),
                  ),
              )
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val first = useCase.sync(SyncRequest(child.id))
          val restarted = StackSyncUseCases(git, NoopReviewPort(), store)
          val second = restarted.sync(SyncRequest(child.id))

          val firstFailure = first.shouldBeInstanceOf<WorkflowResult.Failure>()
          firstFailure.data.code shouldBe FailureCode.SYNC_CONFLICT
          firstFailure.data.workspace?.path?.value shouldBe "/managed/sk-child"
          firstFailure.data.conflicts.map { it.path to it.kind } shouldBe
              listOf("src/main.kt" to "content")
          second.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.SYNC_CONFLICT
          git.restackCalls shouldBe 1
          store.current.syncConflicts.keys shouldBe setOf(child.id)
        }

        test("충돌이 해결된 상태에서 sync --continue를 실행하면, rebase continue 후 충돌 상태를 제거합니다") {
          val child = subTask("sk-child")
          val conflict = SyncConflict(child.id, child, listOf("src/main.kt"), 21)
          val store =
              RecordingStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(child),
                      syncConflicts = mapOf(child.id to conflict),
                  ),
              )
          val git = RecordingGit()
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val result = useCase.sync(SyncRequest(child.id, continueSync = true))

          result.shouldBeInstanceOf<WorkflowResult.Success<SyncResponse>>()
          git.continueCalls shouldBe 1
          git.restackCalls shouldBe 0
          store.current.syncConflicts shouldBe emptyMap()
        }

        test("활성 충돌 상태에서 sync --abort를 실행하면, rebase abort 후 충돌 상태를 제거합니다") {
          val child = subTask("sk-child")
          val conflict = SyncConflict(child.id, child, listOf("src/main.kt"), 21)
          val store =
              RecordingStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(child),
                      syncConflicts = mapOf(child.id to conflict),
                  ),
              )
          val git = RecordingGit()
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val result = useCase.sync(SyncRequest(child.id, abort = true))

          result.shouldBeInstanceOf<WorkflowResult.Success<SyncResponse>>()
          git.abortCalls shouldBe 1
          store.current.syncConflicts shouldBe emptyMap()
        }

        test("동기화 취소 전에 기준 revision을 확인하지 못하면, rebase abort와 충돌 상태 변경을 수행하지 않습니다") {
          val child = subTask("sk-child")
          val conflict = SyncConflict(child.id, child, listOf("src/main.kt"), 21)
          val store =
              RecordingStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      subTasks = listOf(child),
                      syncConflicts = mapOf(child.id to conflict),
                  ),
              )
          val git =
              RecordingGit(
                  refreshMainResult =
                      PortResult.Failure(PortError("REMOTE_FAILURE", "main 조회에 실패했습니다."))
              )
          val useCase = StackSyncUseCases(git, NoopReviewPort(), store)

          val result = useCase.sync(SyncRequest(child.id, abort = true))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.EXTERNAL_FAILURE
          git.abortCalls shouldBe 0
          store.current.syncConflicts.keys shouldBe setOf(child.id)
        }
      }
    })

private fun subTask(
    id: String,
    state: SubTaskState = SubTaskState.DEVELOPMENT,
    requires: String? = null,
): SubTask =
    SubTask(
        id = id,
        taskId = "task-1",
        title = id,
        state = state,
        workspace = Workspace("ws-$id", id, WorkspacePath("/managed/$id"), id),
        requires = requires,
    )

private class RecordingGit(
    var restackResponse: RestackResponse =
        RestackResponse(
            revision = "child-revision",
            fingerprint = "child-fingerprint",
            diffChanged = false,
            change = ChangeReceipt("restack-1", "restack"),
        ),
    var refreshMainResult: PortResult<MainRevisionResponse> =
        PortResult.Success(MainRevisionResponse("main-revision")),
) : GitPort {
  var restackCalls = 0
  var continueCalls = 0
  var abortCalls = 0
  var lastRestack: RestackRequest? = null

  override fun refreshMain(request: MainRevisionRequest) = refreshMainResult

  override fun inspect(request: GitInspectRequest) =
      PortResult.Success(
          GitInspectResponse(
              GitStatus(
                  revision =
                      if (request.workspaceId.contains("parent")) "parent-revision"
                      else "child-revision",
                  fingerprint = "fingerprint-${request.workspaceId}",
                  dirty = false,
              ),
          ),
      )

  override fun createBranch(request: CreateBranchRequest) = error("not used")

  override fun createWorktree(request: CreateWorktreeRequest) = error("not used")

  override fun restack(request: RestackRequest): PortResult<RestackResponse> {
    restackCalls += 1
    lastRestack = request
    return PortResult.Success(restackResponse)
  }

  override fun continueRestack(
      request: ContinueRestackRequest
  ): PortResult<ContinueRestackResponse> {
    continueCalls += 1
    return PortResult.Success(ContinueRestackResponse(ChangeReceipt("continue-1", "continue")))
  }

  override fun abortRestack(request: AbortRestackRequest): PortResult<AbortRestackResponse> {
    abortCalls += 1
    return PortResult.Success(AbortRestackResponse(ChangeReceipt("abort-1", "abort")))
  }

  override fun removeBranch(request: RemoveBranchRequest) = error("not used")
}

private class RecordingStore(var current: WorkflowStoreSnapshot) : WorkflowStorePort {
  var written: WorkflowStoreSnapshot? = null

  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN),
      )

  override fun commit(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.COMMITTED),
      )

  override fun rollback(request: StoreTransactionRequest) =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK),
      )

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    written = request.snapshot
    current = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest) = error("not used")
}

private class NoopReviewPort : ReviewPort {
  override fun open(request: OpenReviewRequest) = error("not used")

  override fun get(request: GetReviewRequest) = error("not used")

  override fun update(request: UpdateReviewRequest) = error("not used")

  override fun comment(request: AddReviewCommentRequest) = error("not used")

  override fun reply(request: ReplyReviewThreadRequest) = error("not used")

  override fun resolve(request: ResolveReviewThreadRequest) = error("not used")

  override fun ready(request: ReadyReviewRequest) = error("not used")

  override fun approve(request: ApproveReviewRequest) = error("not used")
}
