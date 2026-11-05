package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.adapter.store.OkioWorkflowStoreAdapter
import io.springkit.workflow.adapter.store.StoreIdAdapter
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.DeploymentRecorded
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseRecorded
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ReleaseLifecycleUseCasesTest :
    FunSpec({
      context("Production 후보를 공개할 때") {
        test("내부 검수가 성공하면, AWAITING_RELEASE_APPROVAL과 사람 승인 행동을 반환합니다") {
          val store = ReleaseRecordingStore(snapshot())
          val releasePort = ReleaseRecordingPort()
          val result = useCase(store, releasePort).create(createRequest())

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<ReleaseLifecycleResponse>>()
          success.data.release.state shouldBe ReleaseState.AWAITING_RELEASE_APPROVAL
          success.data.next.single().action shouldBe "approve_release"
          store.written?.eventLog?.events.orEmpty() shouldContain
              ReleaseRecorded("release-1", ReleaseState.AWAITING_RELEASE_APPROVAL, 0)
        }

        test("Production Event를 다시 처리하면, Release를 중복 생성하지 않고 기존 결과를 반환합니다") {
          val store = ReleaseRecordingStore(snapshot())
          val releasePort = ReleaseRecordingPort()
          val useCase = useCase(store, releasePort)

          useCase.handleDeployment(
              DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
          )
          val result =
              useCase.handleDeployment(
                  DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
              )

          result.shouldBeInstanceOf<WorkflowResult.Success<ReleaseLifecycleResponse?>>()
          releasePort.createCalls shouldBe 1
          releasePort.validateCalls shouldBe 1
        }

        test("여러 SubTask가 포함되어도 candidate당 Release 하나를 생성합니다") {
          val store =
              ReleaseRecordingStore(snapshot(includedSubTaskIds = listOf("sk-101", "sk-102")))
          val releasePort = ReleaseRecordingPort()
          val result =
              useCase(store, releasePort)
                  .handleDeploymentAll(
                      DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
                  )

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<List<ReleaseLifecycleResponse>>>()
          success.data.map { it.release.candidateId } shouldBe listOf("candidate-1")
          success.data.map { it.release.id }.distinct().size shouldBe 1
          releasePort.createCalls shouldBe 1
          releasePort.validateCalls shouldBe 1
          store.written?.releases?.map { it.candidateId } shouldBe listOf("candidate-1")
        }

        test("실제 Store에서 Release ID를 발급하면, candidate에 연결해 저장합니다") {
          val fileSystem = FakeFileSystem()
          val path = "/workflow/state.json".toPath()
          val store = OkioWorkflowStoreAdapter(fileSystem, path)
          val initial =
              snapshot(includedSubTaskIds = listOf("sk-101", "sk-102")).copy(revision = "0")
          writeSnapshot(store, initial)
          val releasePort = ReleaseRecordingPort()

          val result =
              ReleaseLifecycleUseCases(store, releasePort, idPort = StoreIdAdapter(store))
                  .handleDeploymentAll(
                      DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
                  )

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<List<ReleaseLifecycleResponse>>>()
          success.data.map { it.release.id } shouldBe listOf("rel-1")
          success.data.map { it.release.candidateId } shouldBe listOf("candidate-1")
          releasePort.createCalls shouldBe 1
          releasePort.validateCalls shouldBe 1
          val stored =
              store
                  .snapshot(StoreSnapshotRequest())
                  .shouldBeInstanceOf<PortResult.Success<StoreSnapshotResponse>>()
          stored.value.snapshot.revision shouldBe "3"
          stored.value.snapshot.sequence.release shouldBe 1
          stored.value.snapshot.releases.map { it.candidateId } shouldBe listOf("candidate-1")
        }

        test("candidate Release가 이미 있으면, 재처리에서 기존 Release를 반환합니다") {
          val existing =
              release(
                  state = ReleaseState.AWAITING_RELEASE_APPROVAL,
              )
          val store = ReleaseRecordingStore(snapshot(release = existing))
          val releasePort = ReleaseRecordingPort(existing)
          val result =
              useCase(store, releasePort)
                  .handleDeploymentAll(
                      DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
                  )

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<List<ReleaseLifecycleResponse>>>()
          success.data.map { it.release.id } shouldBe listOf(existing.id)
          releasePort.createCalls shouldBe 0
          releasePort.validateCalls shouldBe 0
        }

        test("SubTask 정보가 없어도 Production candidate에 Release를 생성합니다") {
          val store =
              ReleaseRecordingStore(
                  snapshot(includedSubTaskIds = emptyList(), includeSubTasks = false)
              )
          val result =
              useCase(store, ReleaseRecordingPort())
                  .handleDeploymentAll(
                      DeploymentRecorded("candidate-1", DeploymentCandidateState.PRODUCTION)
                  )

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<List<ReleaseLifecycleResponse>>>()
          success.data.size shouldBe 1
          success.data.single().release.candidateId shouldBe "candidate-1"
        }
      }

      context("점진 공개가 완료된 상태에서") {
        test("rollout 완료 Event를 반영하면, CLEANUP_REQUIRED와 Cleanup SubTask 행동을 반환합니다") {
          val release = release(state = ReleaseState.ROLLOUT)
          val store = ReleaseRecordingStore(snapshot(release = release))
          val result =
              useCase(store, ReleaseRecordingPort(release))
                  .handle(ReleaseRecorded(release.id, ReleaseState.RELEASED))

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<ReleaseLifecycleResponse>>()
          success.data.release.state shouldBe ReleaseState.CLEANUP_REQUIRED
          success.data.next.single().action shouldBe "create_cleanup_subtask"
        }

        test("오래된 Release Event를 반영하면, 현재 상태를 되돌리지 않습니다") {
          val release = release(state = ReleaseState.CLEANUP_REQUIRED)
          val store = ReleaseRecordingStore(snapshot(release = release))
          val result =
              useCase(store, ReleaseRecordingPort(release))
                  .handle(ReleaseRecorded(release.id, ReleaseState.INTERNAL_VALIDATION))

          val success =
              result.shouldBeInstanceOf<WorkflowResult.Success<ReleaseLifecycleResponse>>()
          success.data.release.state shouldBe ReleaseState.CLEANUP_REQUIRED
          store.written shouldBe null
        }
      }

      context("Release 공급자 또는 Store 작업이 실패할 때") {
        test("Store 쓰기가 실패하면, 공급자 변경을 역순으로 보상하고 롤백합니다") {
          val store = ReleaseRecordingStore(snapshot(), failWrite = true)
          val releasePort = ReleaseRecordingPort()
          val compensation = ReleaseRecordingCompensation()
          val result = useCase(store, releasePort, compensation).create(createRequest())

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STATE_CONFLICT
          compensation.operations shouldBe listOf("validate", "create")
          store.calls shouldBe listOf("snapshot", "begin", "snapshot", "write", "rollback")
          store.written shouldBe null
        }

        test("내부 검수가 실패하면, Release를 저장하지 않고 생성 변경을 보상합니다") {
          val store = ReleaseRecordingStore(snapshot())
          val releasePort = ReleaseRecordingPort(failValidation = true)
          val compensation = ReleaseRecordingCompensation()
          val result = useCase(store, releasePort, compensation).create(createRequest())

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.EXTERNAL_FAILURE
          compensation.operations shouldBe listOf("create")
          store.calls shouldBe listOf("snapshot", "begin", "snapshot", "rollback")
          store.written shouldBe null
        }
      }
    })

private fun createRequest() =
    CreateReleaseLifecycleRequest(
        candidateId = "candidate-1",
        releaseId = "release-1",
        requestId = "request-1",
    )

private fun snapshot(
    release: Release? = null,
    includedSubTaskIds: List<String> = listOf("sk-101"),
    includeSubTasks: Boolean = true,
): WorkflowStoreSnapshot =
    WorkflowStoreSnapshot(
        revision = "store-1",
        subTasks =
            if (includeSubTasks) {
              includedSubTaskIds.mapIndexed { index, id ->
                SubTask(id, "task-1", "SubTask ${index + 1}")
              }
            } else {
              emptyList()
            },
        candidates = listOf(productionCandidate(includedSubTaskIds)),
        releases = listOfNotNull(release),
    )

private fun writeSnapshot(store: WorkflowStorePort, snapshot: WorkflowStoreSnapshot) {
  val transaction = StoreTransactionRequest("seed", snapshot.revision, "seed")
  store.begin(transaction)
  store.write(StoreWriteRequest(transaction.transactionId, snapshot.revision, snapshot))
  store.commit(transaction)
}

private fun productionCandidate(includedSubTaskIds: List<String>) =
    DeploymentCandidate(
        id = "candidate-1",
        mainRevision = "main-1",
        includedSubTasks = includedSubTaskIds,
        risks = includedSubTaskIds.associateWith { Risk.NORMAL },
        validations = listOf(Validation("validation-1", "test", ValidationStatus.PASSED)),
        state = DeploymentCandidateState.PRODUCTION,
    )

private fun release(
    state: ReleaseState,
    internalValidationPassed: Boolean = true,
) =
    Release(
        id = "release-1",
        candidateId = "candidate-1",
        state = state,
        productionReady = true,
        internalValidationPassed = internalValidationPassed,
    )

private fun useCase(
    store: ReleaseRecordingStore,
    releasePort: ReleaseRecordingPort,
    compensation: ReleaseRecordingCompensation = ReleaseRecordingCompensation(),
) =
    ReleaseLifecycleUseCases(
        store,
        releasePort,
        idPort = ReleaseLifecycleIdPort(),
        compensationPort = compensation,
    )

private class ReleaseLifecycleIdPort : IdPort {
  private var nextReleaseId = 1

  override fun issue(request: IssueIdRequest): PortResult<IssueIdResponse> =
      PortResult.Success(
          IssueIdResponse(
              listOf(
                  IssuedId(
                      request.kind,
                      if (request.kind == IdKind.RELEASE) "release-${nextReleaseId++}"
                      else "tx-${request.requestId}",
                  )
              )
          )
      )
}

private class ReleaseRecordingStore(
    private var current: WorkflowStoreSnapshot,
    private val failWrite: Boolean = false,
) : WorkflowStorePort {
  val calls = mutableListOf<String>()
  var written: WorkflowStoreSnapshot? = null

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    calls += "begin"
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN)
    )
  }

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> {
    calls += "snapshot"
    return PortResult.Success(StoreSnapshotResponse(current))
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    calls += "write"
    if (failWrite) return PortResult.Failure(PortError("STATE_CONFLICT", "write failed"))
    written = request.snapshot
    current = request.snapshot.copy(revision = "store-2")
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      PortResult.Failure(PortError("UNSUPPORTED", "append is not used"))

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    calls += "commit"
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.COMMITTED)
    )
  }

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    calls += "rollback"
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK)
    )
  }
}

private class ReleaseRecordingPort(
    initial: Release? = null,
    private val failValidation: Boolean = false,
) : ReleasePort {
  private val current =
      mutableMapOf<String, Release>().apply {
        initial?.let { put(it.id, it) }
      }
  var createCalls = 0
  var validateCalls = 0

  override fun get(request: GetReleaseRequest): PortResult<GetReleaseResponse> =
      PortResult.Success(
          GetReleaseResponse(
              current.values.filter { request.releaseId == null || it.id == request.releaseId }
          )
      )

  override fun create(request: CreateReleaseRequest): PortResult<CreateReleaseResponse> {
    createCalls += 1
    val created =
        Release(
            id = request.releaseId,
            candidateId = request.candidateId,
            state = ReleaseState.SAFE_DEFAULT,
        )
    current[created.id] = created
    return PortResult.Success(CreateReleaseResponse(created, change("create")))
  }

  override fun validateInternal(
      request: ValidateReleaseRequest
  ): PortResult<ValidateReleaseResponse> {
    validateCalls += 1
    if (failValidation)
        return PortResult.Failure(PortError("EXTERNAL_FAILURE", "validation failed"))
    val validated =
        current
            .getValue(request.releaseId)
            .copy(state = ReleaseState.INTERNAL_VALIDATION, internalValidationPassed = true)
    current[validated.id] = validated
    return PortResult.Success(ValidateReleaseResponse(validated, change("validate")))
  }

  override fun start(request: StartReleaseRequest): PortResult<StartReleaseResponse> =
      PortResult.Failure(PortError("UNSUPPORTED", "start is not used"))

  override fun continueRollout(
      request: ContinueReleaseRequest
  ): PortResult<ContinueReleaseResponse> =
      PortResult.Failure(PortError("UNSUPPORTED", "continue is not used"))
}

private class ReleaseRecordingCompensation : CompensationPort {
  val operations = mutableListOf<String>()

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    operations += request.change.operation
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private fun change(operation: String) =
    ChangeReceipt(
        id = "change-$operation",
        operation = operation,
        compensation = Compensation("comp-$operation", "undo-$operation", "undo-$operation"),
    )
