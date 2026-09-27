package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskCleanupState
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class StatusUseCaseTest :
    FunSpec({
      context("CLI 선택자를 검증하는 상황에서") {
        test("두 개 이상의 선택자를 지정하면, 잘못된 대상 선택으로 거부합니다") {
          val result =
              StatusUseCase(FakeStatusStore())
                  .execute(
                      StatusRequest(subTaskId = "sk-101", taskId = "TASK-1"),
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_TARGET_SELECTION
        }

        test("잘못된 선택자를 지정하면, 저장소를 읽기 전에 잘못된 대상 선택으로 거부합니다") {
          val result =
              StatusUseCase(FailingStatusStore())
                  .execute(StatusRequest(subTaskId = "sk-101", taskId = "TASK-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_TARGET_SELECTION
        }
      }

      context("상태 저장소 조회가 실패하는 상황에서") {
        test("저장소가 실패를 반환하면, 저장소 실패 코드와 메시지를 반환합니다") {
          val result =
              StatusUseCase(FailingStatusStore()).execute(StatusRequest(subTaskId = "sk-101"))

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.STORE_FAILURE
          failure.message shouldBe "store is unavailable"
        }
      }

      context("상태 대상이 존재하지 않는 상황에서") {
        test("없는 subtask를 조회하면, 대상이 지정된 subtask 없음 오류를 반환합니다") {
          val result = StatusUseCase(FakeStatusStore()).execute(StatusRequest(subTaskId = "sk-404"))

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.SUBTASK_NOT_FOUND
          failure.blockedBy.single().target shouldBe "sk-404"
        }

        test("없는 deployment candidate와 release를 조회하면, 서로 다른 없음 오류를 반환합니다") {
          val candidateResult =
              StatusUseCase(FakeStatusStore()).execute(StatusRequest(candidateId = "dc-404"))
          val releaseResult =
              StatusUseCase(FakeStatusStore()).execute(StatusRequest(releaseId = "rel-404"))

          candidateResult.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.DEPLOYMENT_NOT_FOUND
          releaseResult.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.RELEASE_NOT_FOUND
        }

        test("현재 workspace를 조회할 때 workspace가 없으면, workspace 없음 오류를 반환합니다") {
          val result = StatusUseCase(FakeStatusStore()).execute()

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.WORKSPACE_NOT_FOUND
        }
      }

      context("정리가 완료된 workspace의 상태를 조회하는 상황에서") {
        test("완료된 workspace이면, 삭제된 경로를 다시 검사하지 않고 상태를 반환합니다") {
          val workspace =
              Workspace(
                  id = "ws-sk-parent",
                  subTaskId = "sk-parent",
                  path = WorkspacePath("/managed/sk-parent"),
                  branch = "sk-parent",
              )
          val snapshot =
              WorkflowStoreSnapshot(
                  revision = "store-1",
                  subTasks =
                      listOf(
                          SubTask(
                              id = "sk-parent",
                              taskId = "task-1",
                              title = "정리 완료",
                              state = SubTaskState.MERGED,
                              workspace = workspace,
                              cleanupState = SubTaskCleanupState.COMPLETED,
                          )
                      ),
              )

          val result =
              StatusUseCase(
                      FakeStatusStore(snapshot),
                      workspacePort =
                          FixedStatusWorkspacePort(
                              PortResult.Success(WorkspaceLookupResponse(workspace))
                          ),
                  )
                  .execute()

          val status = result.shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>().data
          status.subTask?.id shouldBe "sk-parent"
          status.workspace shouldBe null
        }

        test("관리 worktree가 아닌 현재 경로이면, 완료 SubTask를 임의로 선택하지 않습니다") {
          val snapshot =
              WorkflowStoreSnapshot(
                  revision = "store-1",
                  subTasks =
                      listOf(
                          SubTask(
                              id = "sk-parent",
                              taskId = "task-1",
                              title = "정리 완료",
                              state = SubTaskState.MERGED,
                              cleanupState = SubTaskCleanupState.COMPLETED,
                          )
                      ),
              )

          val result =
              StatusUseCase(
                      FakeStatusStore(snapshot),
                      workspacePort =
                          FixedStatusWorkspacePort(
                              PortResult.Failure(
                                  PortError("NOT_MANAGED_WORKTREE", "현재 경로는 관리 대상이 아닙니다")
                              )
                          ),
                  )
                  .execute()

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.WORKSPACE_NOT_FOUND
        }

        test("원격 branch만 제거되었으면, 남아 있는 workspace를 계속 표시합니다") {
          val workspace =
              Workspace(
                  id = "ws-sk-parent",
                  subTaskId = "sk-parent",
                  path = WorkspacePath("/managed/sk-parent"),
                  branch = "sk-parent",
              )
          val snapshot =
              WorkflowStoreSnapshot(
                  revision = "store-1",
                  subTasks =
                      listOf(
                          SubTask(
                              id = "sk-parent",
                              taskId = "task-1",
                              title = "원격 정리 진행 중",
                              state = SubTaskState.MERGED,
                              workspace = workspace,
                              cleanupState = SubTaskCleanupState.REMOTE_BRANCH_REMOVED,
                          )
                      ),
              )

          val result =
              StatusUseCase(FakeStatusStore(snapshot))
                  .execute(StatusRequest(subTaskId = "sk-parent"))

          result
              .shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>()
              .data
              .workspace
              ?.workspace
              ?.id shouldBe workspace.id
        }
      }

      context("애플리케이션 호출자의 선택자를 확인하는 상황에서") {
        test("typed selector를 지정하면, 동일한 선택자를 보존합니다") {
          val request = StatusRequest(selector = StatusSelector.SubTask("sk-101"))

          request.selected().shouldBeInstanceOf<StatusSelection.Typed>().selector shouldBe
              StatusSelector.SubTask("sk-101")
        }
      }

      context("Review가 Merge Queue 차단 조건을 가진 상황에서") {
        test("Review가 Merge Queue를 차단하면, 차단 사유와 해결 행동을 반환하고 승인을 다음 행동으로 제안하지 않습니다") {
          val snapshot =
              reviewSnapshot(SubTaskState.REVIEW, PullRequestState.REVIEW, CiStatus.PENDING)

          val result =
              StatusUseCase(FakeStatusStore(snapshot)).execute(StatusRequest(subTaskId = "sk-101"))

          val status = result.shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>().data
          status.blockedBy.map { it.code } shouldBe listOf("CI_NOT_PASSED")
          status.next.map { it.action } shouldBe listOf("await_ci")
          status.next.map { it.action } shouldNotContain "approve_change"
        }

        test("Draft Review이면, 정규화된 ready 명령을 반환합니다") {
          val snapshot =
              reviewSnapshot(SubTaskState.DRAFT, PullRequestState.DRAFT, CiStatus.PENDING)

          val result =
              StatusUseCase(FakeStatusStore(snapshot)).execute(StatusRequest(subTaskId = "sk-101"))

          val next = result.shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>().data.next
          next.single().action shouldBe "ready_review"
          next.single().command shouldBe
              "./tools/workflow/bin/workflow gate ready sk-101 --review-revision rv-1"
        }
      }

      context("CI 상태를 조회하는 상황에서") {
        test("Review의 provider revision이 없으면, diff identity를 쓰지 않고 CI를 미완료로 둡니다") {
          val ciPort = RecordingCiPort()
          val result =
              StatusUseCase(
                      storePort =
                          FakeStatusStore(
                              reviewSnapshot(
                                  SubTaskState.REVIEW,
                                  PullRequestState.REVIEW,
                                  CiStatus.PASSED,
                              )
                          ),
                      ciPort = ciPort,
                  )
                  .execute(StatusRequest(subTaskId = "sk-101"))

          result
              .shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>()
              .data
              .review
              ?.ci shouldBe CiStatus.PENDING
          ciPort.revision shouldBe null
        }

        test("provider revision이 있으면, CI 공급자에 provider revision을 전달합니다") {
          val snapshot =
              reviewSnapshot(SubTaskState.REVIEW, PullRequestState.REVIEW, CiStatus.PENDING)
                  .copy(
                      pullRequests =
                          listOf(
                              reviewSnapshot(
                                      SubTaskState.REVIEW,
                                      PullRequestState.REVIEW,
                                      CiStatus.PENDING,
                                  )
                                  .pullRequests
                                  .single()
                                  .copy(
                                      changeRevision =
                                          ChangeRevision(
                                              "cr-1",
                                              1,
                                              Diff("diff-1"),
                                              providerRevision = "head-1",
                                          )
                                  )
                          )
                  )
          val ciPort = RecordingCiPort()

          val result =
              StatusUseCase(FakeStatusStore(snapshot), ciPort = ciPort)
                  .execute(StatusRequest(subTaskId = "sk-101"))

          result.shouldBeInstanceOf<WorkflowResult.Success<StatusResponse>>()
          result.data.review?.ci shouldBe CiStatus.PASSED
          ciPort.revision shouldBe "head-1"
        }
      }
    })

private fun reviewSnapshot(
    subTaskState: SubTaskState,
    reviewState: PullRequestState,
    ciStatus: CiStatus,
): WorkflowStoreSnapshot =
    WorkflowStoreSnapshot(
        revision = "store-1",
        subTasks =
            listOf(
                SubTask(
                    id = "sk-101",
                    taskId = "task-1",
                    title = "상태 계약",
                    state = subTaskState,
                    pullRequestId = "pr-1",
                )
            ),
        pullRequests =
            listOf(
                PullRequest(
                    id = "pr-1",
                    subTaskId = "sk-101",
                    title = "상태 계약",
                    body = "상태 계약을 검증합니다.",
                    base = "main",
                    state = reviewState,
                    reviewRevision = ReviewRevision("rv-1", 1, "상태 계약을 검증합니다."),
                    changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
                    ci = ciStatus,
                )
            ),
    )

private class FakeStatusStore(
    private val current: WorkflowStoreSnapshot = WorkflowStoreSnapshot("store-1")
) : WorkflowStorePort {
  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> = unused()

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> = unused()

  private fun <T> unused(): PortResult<T> =
      PortResult.Failure(PortError("UNUSED", "not used by this test"))
}

private class FixedStatusWorkspacePort(private val result: PortResult<WorkspaceLookupResponse>) :
    WorkspacePort {
  override fun get(request: WorkspaceLookupRequest) = result

  override fun create(request: CreateWorkspaceRequest) = error("not used")

  override fun delete(request: DeleteWorkspaceRequest) = error("not used")
}

private class FailingStatusStore : WorkflowStorePort {
  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> =
      PortResult.Failure(PortError("STORE_DOWN", "store is unavailable"))

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      unused()

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> = unused()

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> = unused()

  private fun <T> unused(): PortResult<T> =
      PortResult.Failure(PortError("UNUSED", "not used by this test"))
}

private class RecordingCiPort : CiPort {
  var revision: String? = null

  override fun start(request: StartCiRequest): PortResult<StartCiResponse> =
      PortResult.Failure(PortError("UNUSED", "not used by this test"))

  override fun get(request: GetCiRequest): PortResult<GetCiResponse> {
    revision = request.revision
    return PortResult.Success(GetCiResponse(emptyList(), CiStatus.PASSED))
  }
}
