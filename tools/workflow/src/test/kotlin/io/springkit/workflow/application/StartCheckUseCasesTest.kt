package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.IdSequence
import io.springkit.workflow.domain.StartRequestKey
import io.springkit.workflow.domain.StartRequestRecord
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath

class StartCheckUseCasesTest :
    FunSpec({
      context("Worktree 변경을 검증할 때") {
        test("현재 revision과 fingerprint를 검증하면, 게시 가능한 결과를 저장합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val git = FakeGit(GitStatus("commit-1", "fingerprint-1", dirty = true))
          val validations = RecordingValidationPort()
          val store = FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask)))
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  git,
                  store,
                  validations,
              )

          val result = useCase.execute(CheckRequest(workspaceId = workspace.id))
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<CheckResponse>>()

          validations.requiredNames shouldBe listOf("test", "build", "static")
          validations.request?.revision shouldBe "commit-1"
          validations.request?.fingerprint shouldBe "fingerprint-1"
          success.data.publishable shouldBe true
          success.data.summary.fingerprint shouldBe "fingerprint-1"
          store.written?.checks?.get("sk-101") shouldBe success.data.summary
        }

        test("ValidationPort summary에서 static 검사가 빠지면, 불변식 오류로 거부합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val incompleteSummary =
              CheckSummary(
                  fingerprint = "fingerprint-1",
                  revision = "commit-1",
                  checks =
                      listOf("test", "build").map { id ->
                        CheckResult(
                            id = id,
                            name = id,
                            status = ValidationStatus.PASSED,
                            fingerprint = "fingerprint-1",
                            revision = "commit-1",
                        )
                      },
              )
          val store = FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask)))
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  FakeGit(GitStatus("commit-1", "fingerprint-1", dirty = true)),
                  store,
                  RecordingValidationPort(summary = incompleteSummary),
              )

          val result = useCase.execute(CheckRequest(workspaceId = workspace.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("필수 static 검사가 FAILED이면, 결과를 저장하고 게시 불가로 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val failedSummary =
              CheckSummary(
                  fingerprint = "fingerprint-1",
                  revision = "commit-1",
                  checks =
                      listOf("test", "build", "static").map { id ->
                        val failed = id == "static"
                        CheckResult(
                            id = id,
                            name = id,
                            status =
                                if (failed) ValidationStatus.FAILED else ValidationStatus.PASSED,
                            fingerprint = "fingerprint-1",
                            revision = "commit-1",
                            message = if (failed) "formatting failed" else null,
                        )
                      },
              )
          val store = FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask)))
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  FakeGit(GitStatus("commit-1", "fingerprint-1", dirty = true)),
                  store,
                  RecordingValidationPort(summary = failedSummary),
              )

          val response =
              useCase
                  .execute(CheckRequest(workspaceId = workspace.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<CheckResponse>>()
                  .data

          response.publishable shouldBe false
          store.written?.checks?.get("sk-101") shouldBe failedSummary
        }

        test("다른 revision의 검증 결과가 반환되면, 오래된 검증 결과로 거절합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val validations =
              RecordingValidationPort(
                  summary =
                      CheckSummary(
                          fingerprint = "old-fingerprint",
                          revision = "old-revision",
                          checks = emptyList(),
                      ),
              )
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  FakeGit(GitStatus("commit-1", "fingerprint-1", dirty = false)),
                  FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask))),
                  validations,
              )

          val result = useCase.execute(CheckRequest(workspaceId = workspace.id))
          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()

          failure.data.code shouldBe io.springkit.workflow.domain.FailureCode.STALE_REVISION
          failure.data.next.map { it.action } shouldNotContain "open_review"
        }
      }

      context("원격 게시 가능성을 확인할 때") {
        test("필수 검증이 실패하면 원격 dry-run을 호출하지 않습니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val failedSummary =
              CheckSummary(
                  fingerprint = "fingerprint-1",
                  revision = "commit-1",
                  checks =
                      listOf("test", "build", "static").map { id ->
                        CheckResult(
                            id = id,
                            name = id,
                            status = ValidationStatus.FAILED,
                            fingerprint = "fingerprint-1",
                            revision = "commit-1",
                            message = "검증 실패",
                        )
                      },
              )
          val git = FakeGit(GitStatus("commit-1", "fingerprint-1", dirty = false))
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  git,
                  FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask))),
                  RecordingValidationPort(summary = failedSummary),
              )

          val response =
              useCase
                  .execute(CheckRequest(workspaceId = workspace.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<CheckResponse>>()
                  .data

          response.publishable shouldBe false
          git.publishCheckRequest shouldBe null
        }

        test("검증이 통과해도 push dry-run이 거부하면 게시 불가를 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val git =
              FakeGit(
                  GitStatus("commit-1", "fingerprint-1", dirty = false),
                  PortResult.Success(
                      CheckRemotePushResponse(
                          publishable = false,
                          message = "remote rejected update",
                      )
                  ),
              )
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  git,
                  FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask))),
                  RecordingValidationPort(),
              )

          val response =
              useCase
                  .execute(CheckRequest(workspaceId = workspace.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<CheckResponse>>()
                  .data

          response.publishable shouldBe false
          response.publishabilityMessage shouldBe "remote rejected update"
          git.publishCheckRequest shouldBe CheckRemotePushRequest(workspace.id, subTask.branch)
        }

        test("dry-run을 실행할 수 없으면 게시 불가와 실패 원인을 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "change")
          val git =
              FakeGit(
                  GitStatus("commit-1", "fingerprint-1", dirty = false),
                  PortResult.Failure(PortError("GIT_COMMAND_FAILED", "network unavailable")),
              )
          val useCase =
              CheckUseCase(
                  FakeWorkspace(workspace),
                  git,
                  FakeStore(WorkflowStoreSnapshot("store-1", subTasks = listOf(subTask))),
                  RecordingValidationPort(),
              )

          val response =
              useCase
                  .execute(CheckRequest(workspaceId = workspace.id))
                  .shouldBeInstanceOf<WorkflowResult.Success<CheckResponse>>()
                  .data

          response.publishable shouldBe false
          response.publishabilityMessage shouldBe "network unavailable"
        }
      }

      context("SubTask 작업을 시작할 때") {
        test("같은 요청을 다시 실행하면, 기존 Workspace를 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val existing = SubTask("sk-101", "task-1", "change", workspace = workspace)
          val task =
              Task("task-1", ExternalTaskId("TASK-1"), "task", subTaskIds = listOf(existing.id))
          val key = StartRequestKey("TASK-1", "request-1")
          val taskPort = ExistingTaskPort(task, existing)
          val store =
              RecordingStartStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      tasks = listOf(task),
                      subTasks = listOf(existing),
                      workspaces = listOf(workspace),
                      startRequests =
                          mapOf(
                              key to
                                  StartRequestRecord(
                                      key = key,
                                      subTaskId = existing.id,
                                      title = "change",
                                  ),
                          ),
                  ),
              )
          val useCase =
              StartUseCase(
                  taskPort,
                  StartGit(),
                  FakeWorkspace(workspace),
                  store,
                  projectPrefix = "sk",
              )

          val result =
              useCase.execute(
                  StartRequest(ExternalTaskId("TASK-1"), "request-1", "change"),
              )
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<StartResponse>>()

          success.data.idempotent shouldBe true
          success.data.subTask.id shouldBe existing.id
          taskPort.lastCreateRequest shouldBe null
          store.rollbackCount shouldBe 0
        }

        test("완료된 시작 요청을 재시도하면, SubTask sequence를 증가시키지 않습니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val existing = SubTask("sk-101", "task-1", "change", workspace = workspace)
          val task =
              Task("task-1", ExternalTaskId("TASK-1"), "task", subTaskIds = listOf(existing.id))
          val key = StartRequestKey("TASK-1", "request-1")
          val taskPort = ExistingTaskPort(task, existing)
          val store =
              RecordingStartStore(
                  WorkflowStoreSnapshot(
                      revision = "store-1",
                      sequence = IdSequence(subTask = 101),
                      tasks = listOf(task),
                      subTasks = listOf(existing),
                      workspaces = listOf(workspace),
                      startRequests =
                          mapOf(
                              key to
                                  StartRequestRecord(
                                      key = key,
                                      subTaskId = existing.id,
                                      title = "change",
                                  ),
                          ),
                  ),
              )
          val useCase =
              StartUseCase(
                  taskPort,
                  StartGit(),
                  FakeWorkspace(workspace),
                  store,
                  projectPrefix = "sk",
              )

          val result =
              useCase.execute(
                  StartRequest(ExternalTaskId("TASK-1"), "request-1", "change"),
              )
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<StartResponse>>()

          success.data.idempotent shouldBe true
          taskPort.lastCreateRequest shouldBe null
          store.beginCount shouldBe 0
          store.written shouldBe null
        }

        test("같은 요청에 다른 메타데이터를 입력하면, 멱등성 충돌로 거절합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("/managed/sk-101"), "sk-101")
          val existing = SubTask("sk-101", "task-1", "original", workspace = workspace)
          val task =
              Task("task-1", ExternalTaskId("TASK-1"), "task", subTaskIds = listOf(existing.id))
          val key = StartRequestKey("TASK-1", "request-1")
          val taskPort = ExistingTaskPort(task, existing)
          val store =
              RecordingStartStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      tasks = listOf(task),
                      subTasks = listOf(existing),
                      workspaces = listOf(workspace),
                      startRequests =
                          mapOf(
                              key to
                                  StartRequestRecord(
                                      key = key,
                                      subTaskId = existing.id,
                                      title = "original",
                                  ),
                          ),
                  ),
              )
          val result =
              StartUseCase(
                      taskPort,
                      StartGit(),
                      FakeWorkspace(workspace),
                      store,
                      compensationPort = RecordingCompensationPort(),
                      projectPrefix = "sk",
                  )
                  .execute(StartRequest(ExternalTaskId("TASK-1"), "request-1", "different"))
          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()

          failure.data.blockedBy.single().code shouldBe "IDEMPOTENCY_CONFLICT"
          taskPort.lastCreateRequest shouldBe null
          store.rollbackCount shouldBe 0
        }

        test("새 SubTask를 시작하면, 발급한 ID와 증가한 sequence를 같은 저장 트랜잭션에 기록합니다") {
          val task = Task("task-1", ExternalTaskId("TASK-1"), "task")
          val subTask = SubTask("sk-101", task.id, "change")
          val store = RecordingStartStore(WorkflowStoreSnapshot("store-1"))
          val taskPort = ExistingTaskPort(task, subTask)
          val useCase =
              StartUseCase(
                  taskPort,
                  StartGit(),
                  FakeWorkspace(
                      Workspace(
                          "ws-1",
                          subTask.id,
                          WorkspacePath("/managed/sk-101"),
                          subTask.branch,
                      ),
                  ),
                  store,
                  projectPrefix = "sk",
              )

          val result =
              useCase.execute(
                  StartRequest(ExternalTaskId("TASK-1"), "request-1", "change"),
              )
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<StartResponse>>()

          success.data.subTask.id shouldBe "sk-101"
          success.data.subTask.workspace shouldBe success.data.workspace
          taskPort.lastCreateRequest?.subTaskId shouldBe "sk-101"
          store.written?.sequence?.subTask shouldBe 101
          store.written?.subTasks?.single()?.workspace shouldBe store.written?.workspaces?.single()
        }

        test("Worktree 생성이 실패하면, 외부 변경을 역순으로 보상하고 상태를 되돌립니다") {
          val task = Task("task-1", ExternalTaskId("TASK-1"), "task")
          val subTask = SubTask("sk-101", "task-1", "change")
          val store = RecordingStartStore(WorkflowStoreSnapshot("store-1"))
          val compensation = RecordingCompensationPort()
          val result =
              StartUseCase(
                      ExistingTaskPort(task, subTask),
                      FailingStartGit(),
                      FakeWorkspace(
                          Workspace(
                              "ws-1",
                              subTask.id,
                              WorkspacePath("/managed/sk-101"),
                              subTask.branch,
                          ),
                      ),
                      store,
                      compensationPort = compensation,
                      projectPrefix = "sk",
                  )
                  .execute(StartRequest(ExternalTaskId("TASK-1"), "request-1", "change"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>()
          store.rollbackCount shouldBe 1
          compensation.ids shouldBe listOf("worktree-change", "branch-change", "task-change")
        }
      }
    })

private class FakeWorkspace(private val workspace: Workspace) : WorkspacePort {
  override fun get(request: WorkspaceLookupRequest) =
      PortResult.Success(WorkspaceLookupResponse(workspace))

  override fun create(request: CreateWorkspaceRequest): PortResult<CreateWorkspaceResponse> =
      PortResult.Success(
          CreateWorkspaceResponse(
              Workspace(
                  request.workspaceId,
                  request.subTaskId,
                  request.path,
                  request.branch,
              ),
              ChangeReceipt("workspace-change", "create-workspace"),
          ),
      )

  override fun delete(request: DeleteWorkspaceRequest): PortResult<DeleteWorkspaceResponse> =
      error("not used")
}

private class ExistingTaskPort(
    private val task: Task,
    private val subTask: SubTask,
) : TaskPort {
  var lastCreateRequest: CreateSubTaskRequest? = null

  override fun get(request: TaskLookupRequest) = PortResult.Success(TaskLookupResponse(task))

  override fun getSubTask(request: SubTaskLookupRequest) =
      PortResult.Success(SubTaskLookupResponse(subTask))

  override fun createSubTask(request: CreateSubTaskRequest) = run {
    lastCreateRequest = request
    PortResult.Success(
        CreateSubTaskResponse(
            task,
            subTask,
            ChangeReceipt(
                "task-change",
                "create-subtask",
                compensation = Compensation("undo-task", "delete-subtask", "request-1"),
            ),
        ),
    )
  }

  override fun updateSubTask(
      request: UpdateExternalSubTaskRequest,
  ): PortResult<UpdateExternalSubTaskResponse> = error("not used")
}

private open class StartGit : GitPort {
  override fun refreshMain(request: MainRevisionRequest) =
      PortResult.Success(MainRevisionResponse("main-1"))

  override fun inspect(request: GitInspectRequest) =
      PortResult.Success(GitInspectResponse(GitStatus("main-1", "main-fingerprint", false)))

  override fun createBranch(request: CreateBranchRequest) =
      PortResult.Success(
          CreateBranchResponse(
              request.branch,
              request.baseRevision,
              ChangeReceipt("branch-change", "create-branch"),
          ),
      )

  override fun createWorktree(
      request: CreateWorktreeRequest,
  ): PortResult<CreateWorktreeResponse> =
      PortResult.Success(
          CreateWorktreeResponse(
              request.workspaceId,
              request.branch,
              request.path,
              ChangeReceipt("worktree-change", "create-worktree"),
          ),
      )

  override fun restack(request: RestackRequest): PortResult<RestackResponse> = error("not used")

  override fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse> =
      error("not used")
}

private class FailingStartGit : StartGit() {
  override fun createWorktree(request: CreateWorktreeRequest) =
      PortResult.Failure(
          PortError("WORKTREE_CREATE_FAILED", "worktree provider failed"),
          ChangeReceipt(
              "worktree-change",
              "create-worktree",
              compensation = Compensation("undo-worktree", "remove-worktree", "request-1"),
          ),
      )

  override fun createBranch(request: CreateBranchRequest) =
      PortResult.Success(
          CreateBranchResponse(
              request.branch,
              request.baseRevision,
              ChangeReceipt(
                  "branch-change",
                  "create-branch",
                  compensation = Compensation("undo-branch", "remove-branch", "request-1"),
              ),
          ),
      )
}

private class RecordingStartStore(private val snapshotValue: WorkflowStoreSnapshot) :
    WorkflowStorePort {
  var rollbackCount = 0
  var beginCount = 0
  var written: WorkflowStoreSnapshot? = null

  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(snapshotValue))

  override fun begin(request: StoreTransactionRequest) = run {
    beginCount += 1
    PortResult.Success(StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN))
  }

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(
              request.transactionId,
              StoreTransactionState.COMMITTED,
              "store-2",
          ),
      )

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    rollbackCount += 1
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK),
    )
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    written = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      PortResult.Success(StoreEventResponse(request.event, "store-2"))
}

private class RecordingCompensationPort : CompensationPort {
  val ids = mutableListOf<String>()

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    ids += request.change.id
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private class FakeGit(
    private val status: GitStatus,
    private val remotePushResult: PortResult<CheckRemotePushResponse> =
        PortResult.Success(CheckRemotePushResponse(publishable = true)),
) : GitPort {
  var publishCheckRequest: CheckRemotePushRequest? = null

  override fun inspect(request: GitInspectRequest) = PortResult.Success(GitInspectResponse(status))

  override fun checkRemotePush(
      request: CheckRemotePushRequest
  ): PortResult<CheckRemotePushResponse> {
    publishCheckRequest = request
    return remotePushResult
  }

  override fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse> =
      error("not used")

  override fun createBranch(request: CreateBranchRequest): PortResult<CreateBranchResponse> =
      error("not used")

  override fun createWorktree(request: CreateWorktreeRequest): PortResult<CreateWorktreeResponse> =
      error("not used")

  override fun restack(request: RestackRequest): PortResult<RestackResponse> = error("not used")

  override fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse> =
      error("not used")
}

private class FakeStore(private val value: WorkflowStoreSnapshot) : WorkflowStorePort {
  var written: WorkflowStoreSnapshot? = null

  override fun snapshot(request: StoreSnapshotRequest) =
      PortResult.Success(StoreSnapshotResponse(value))

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(
              request.transactionId,
              StoreTransactionState.OPEN,
              value.revision,
          )
      )

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(
              request.transactionId,
              StoreTransactionState.COMMITTED,
              "store-2",
          )
      )

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      error("not used")

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    written = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      error("not used")
}

private class RecordingValidationPort(
    private val summary: CheckSummary? = null,
) : ValidationPort {
  var request: RunValidationRequest? = null
  var requiredNames: List<String> = emptyList()

  override fun run(request: RunValidationRequest): PortResult<RunValidationResponse> {
    this.request = request
    requiredNames = request.required.map { it.name }
    val validations = request.required.map { it.copy(status = ValidationStatus.PASSED) }
    val resultSummary =
        summary
            ?: CheckSummary(
                request.fingerprint,
                request.revision,
                validations.map {
                  io.springkit.workflow.domain.CheckResult(
                      it.id,
                      it.name,
                      it.status,
                      request.fingerprint,
                      request.revision,
                  )
                },
            )
    return PortResult.Success(
        RunValidationResponse(
            validations = validations,
            summary = resultSummary,
            change = ChangeReceipt("run-1", "run-validation"),
        ),
    )
  }

  override fun get(request: GetValidationRequest): PortResult<GetValidationResponse> =
      error("not used")
}
