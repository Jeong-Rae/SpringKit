package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.GateRecorded
import io.springkit.workflow.domain.GateType
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult

class DeliveryGateUseCasesTest :
    FunSpec({
      context("고위험 배포 Gate를 실행할 때") {
        test("사람이 AWAITING_DEPLOY_APPROVAL 후보를 승인하면, Canary와 Gate 감사 정보를 함께 저장합니다") {
          val candidate = deployCandidate()
          val store =
              RecordingGateStore(WorkflowStoreSnapshot("store-1", candidates = listOf(candidate)))
          val deployment = RecordingDeploymentPort(candidate)
          val useCase = useCase(store, deployment = deployment)

          val result = useCase.deploy(DeployGateRequest(candidate.id, "request-1"))
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<StartCanaryResponse>>()

          success.data.candidate.state shouldBe DeploymentCandidateState.CANARY
          success.data.candidate shouldBe candidate.copy(state = DeploymentCandidateState.CANARY)
          deployment.startedActor shouldBe human
          store.written?.candidates?.single() shouldBe
              candidate.copy(state = DeploymentCandidateState.CANARY)
          store.written?.eventLog?.events.orEmpty() shouldContain
              GateRecorded(candidate.id, GateType.DEPLOY, human, 1_000)
          store.written?.eventLog?.audits.orEmpty() shouldContain
              AuditEntry(
                  id = "audit-1",
                  actor = human,
                  action = "deploy",
                  targetId = candidate.id,
                  mainRevision = candidate.mainRevision,
                  occurredAtEpochMillis = 1_000,
              )
        }

        test("Feature Flag SubTask의 기본 동작이 안전하면, Canary를 시작합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate, subTasks = listOf(featureFlagSubTask()))
          val deployment = RecordingDeploymentPort(candidate)
          val featureFlag =
              RecordingFeatureFlagPort(PortResult.Success(ValidateFeatureFlagResponse(true)))

          val result =
              useCase(store, deployment = deployment, featureFlagPort = featureFlag)
                  .deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Success<StartCanaryResponse>>()
          featureFlag.calls shouldBe listOf("recommendation-v2")
          deployment.startCalls shouldBe 1
          store.written?.candidates?.single() shouldBe
              candidate.copy(state = DeploymentCandidateState.CANARY)
        }

        test("같은 Feature Flag를 사용하는 SubTask가 여러 개이면, provider를 한 번만 검증합니다") {
          val candidate =
              deployCandidate()
                  .copy(
                      includedSubTasks = listOf("sk-101", "sk-102"),
                      risks = mapOf("sk-101" to Risk.HIGH, "sk-102" to Risk.HIGH),
                  )
          val store =
              storeFor(
                  candidate = candidate,
                  subTasks = listOf(featureFlagSubTask(), featureFlagSubTask("sk-102")),
              )
          val deployment = RecordingDeploymentPort(candidate)
          val featureFlag =
              RecordingFeatureFlagPort(PortResult.Success(ValidateFeatureFlagResponse(true)))

          val result =
              useCase(store, deployment = deployment, featureFlagPort = featureFlag)
                  .deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Success<StartCanaryResponse>>()
          featureFlag.calls shouldBe listOf("recommendation-v2")
          deployment.startCalls shouldBe 1
        }

        test("Feature Flag SubTask의 기본 동작이 안전하지 않으면, Canary를 시작하지 않고 롤백합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate, subTasks = listOf(featureFlagSubTask()))
          val deployment = RecordingDeploymentPort(candidate)
          val featureFlag =
              RecordingFeatureFlagPort(PortResult.Success(ValidateFeatureFlagResponse(false)))

          val result =
              useCase(store, deployment = deployment, featureFlagPort = featureFlag)
                  .deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_GATE_STATE
          deployment.startCalls shouldBe 0
          store.written shouldBe null
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }

        test("Feature Flag provider가 실패하면, Canary를 시작하지 않고 롤백합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate, subTasks = listOf(featureFlagSubTask()))
          val deployment = RecordingDeploymentPort(candidate)
          val featureFlag =
              RecordingFeatureFlagPort(
                  PortResult.Failure(
                      PortError(FailureCode.EXTERNAL_FAILURE.name, "provider failed")
                  )
              )

          val result =
              useCase(store, deployment = deployment, featureFlagPort = featureFlag)
                  .deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.EXTERNAL_FAILURE
          deployment.startCalls shouldBe 0
          store.written shouldBe null
        }

        test("Feature Flag provider가 없으면, Canary를 시작하지 않고 롤백합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate, subTasks = listOf(featureFlagSubTask()))
          val deployment = RecordingDeploymentPort(candidate)

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVALID_GATE_STATE
          deployment.startCalls shouldBe 0
          store.written shouldBe null
        }

        test("사람이 NORMAL 후보를 승인하면, 배포 Gate 차단 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate(risk = Risk.NORMAL)
          val store =
              RecordingGateStore(WorkflowStoreSnapshot("store-1", candidates = listOf(candidate)))
          val deployment = RecordingDeploymentPort(candidate)
          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.DEPLOYMENT_GATE_BLOCKED
          deployment.startCalls shouldBe 0
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }

        test("Agent가 배포 Gate를 실행하면, 사람 필요 오류와 상태 불변 결과를 반환합니다") {
          val candidate = deployCandidate()
          val store =
              RecordingGateStore(WorkflowStoreSnapshot("store-1", candidates = listOf(candidate)))
          val identity = RecordingIdentityPort(Actor("agent-1", ActorKind.AGENT), allowed = false)
          val result =
              DeliveryGateUseCases(
                      store,
                      RecordingDeploymentPort(candidate),
                      RecordingReleasePort(),
                      identity,
                  )
                  .deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.HUMAN_REQUIRED
          store.calls shouldBe emptyList()
          identity.authorizedCapability shouldBe Capability.DEPLOY
        }

        test("Store 쓰기가 실패하면, 시작한 Canary 변경을 보상하고 커밋하지 않습니다") {
          val candidate = deployCandidate()
          val store =
              RecordingGateStore(
                  WorkflowStoreSnapshot("store-1", candidates = listOf(candidate)),
                  failWrite = true,
              )
          val compensation = DeliveryRecordingCompensationPort()
          val deployment = RecordingDeploymentPort(candidate, compensatable = true)
          val result =
              useCase(store, deployment = deployment, compensation = compensation)
                  .deploy(DeployGateRequest(candidate.id, "request-1"))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.STALE_REVISION
          compensation.operations shouldBe listOf("start-canary")
          store.calls shouldBe listOf("begin", "snapshot", "write", "rollback")
          store.written shouldBe null
        }

        test("Deployment provider가 mainRevision을 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment = RecordingDeploymentPort(candidate.copy(mainRevision = "main-2"))

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          deployment.startCalls shouldBe 0
          store.written shouldBe null
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }

        test("Deployment provider가 includedSubTasks를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment =
              RecordingDeploymentPort(
                  candidate.copy(
                      includedSubTasks = listOf("sk-202"),
                      risks = mapOf("sk-202" to Risk.HIGH),
                  )
              )

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Deployment provider가 risks를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment =
              RecordingDeploymentPort(candidate.copy(risks = mapOf("sk-101" to Risk.NORMAL)))

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Deployment provider가 validations를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment =
              RecordingDeploymentPort(
                  candidate.copy(
                      validations =
                          listOf(Validation("validation-2", "test", ValidationStatus.PASSED))
                  )
              )

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Deployment provider가 Gate 상태를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment =
              RecordingDeploymentPort(candidate.copy(state = DeploymentCandidateState.CANARY))

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Deployment start 응답이 validations를 바꾸면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = deployCandidate()
          val store = storeFor(candidate = candidate)
          val deployment =
              RecordingDeploymentPort(
                  candidate,
                  startCandidate =
                      candidate.copy(
                          state = DeploymentCandidateState.CANARY,
                          validations =
                              listOf(Validation("validation-2", "test", ValidationStatus.PASSED)),
                      ),
              )

          val result =
              useCase(store, deployment = deployment).deploy(DeployGateRequest(candidate.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }
      }

      context("외부 공개 Gate를 실행할 때") {
        test("productionReady와 내부 검수를 통과한 공개 대상을 승인하면, Rollout과 Gate 감사 정보를 함께 저장합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store =
              RecordingGateStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      candidates = listOf(candidate),
                      releases = listOf(release),
                  )
              )
          val releasePort = RecordingReleasePort(release)
          val result =
              useCase(store, release = releasePort)
                  .release(ReleaseGateRequest(release.id, "request-2"))
          val success = result.shouldBeInstanceOf<WorkflowResult.Success<StartReleaseResponse>>()

          success.data.release.state shouldBe ReleaseState.ROLLOUT
          success.data.release shouldBe release.copy(state = ReleaseState.ROLLOUT)
          releasePort.startedActor shouldBe human
          store.written?.releases?.single() shouldBe release.copy(state = ReleaseState.ROLLOUT)
          store.written?.eventLog?.events.orEmpty() shouldContain
              GateRecorded(release.id, GateType.RELEASE, human, 1_000)
          store.written?.eventLog?.audits.orEmpty() shouldContain
              AuditEntry(
                  id = "audit-1",
                  actor = human,
                  action = "release",
                  targetId = release.id,
                  mainRevision = candidate.mainRevision,
                  occurredAtEpochMillis = 1_000,
              )
        }

        test("내부 검수가 끝나지 않은 공개 대상을 승인하면, 공개 Gate 차단 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release(internalValidationPassed = false)
          val store =
              RecordingGateStore(
                  WorkflowStoreSnapshot(
                      "store-1",
                      candidates = listOf(candidate),
                      releases = listOf(release),
                  )
              )
          val releasePort = RecordingReleasePort(release)
          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.RELEASE_GATE_BLOCKED
          releasePort.startCalls shouldBe 0
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }

        test("Release provider가 candidateId를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort = RecordingReleasePort(release.copy(candidateId = "candidate-2"))

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          releasePort.startCalls shouldBe 0
          store.written shouldBe null
        }

        test("Release provider가 featureFlagId를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort = RecordingReleasePort(release.copy(featureFlagId = "recommendation-v3"))

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Release provider가 production readiness를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort = RecordingReleasePort(release.copy(productionReady = false))

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Release provider가 내부 검수 readiness를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort = RecordingReleasePort(release.copy(internalValidationPassed = false))

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Release provider가 Gate 상태를 바꾸어 반환하면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort = RecordingReleasePort(release.copy(state = ReleaseState.ROLLOUT))

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
        }

        test("Release start 응답이 featureFlagId를 바꾸면, 불변식 오류와 롤백을 반환합니다") {
          val candidate = productionCandidate()
          val release = release()
          val store = storeFor(candidate = candidate, release = release)
          val releasePort =
              RecordingReleasePort(
                  release,
                  startRelease =
                      release.copy(
                          state = ReleaseState.ROLLOUT,
                          featureFlagId = "recommendation-v3",
                      ),
              )

          val result = useCase(store, release = releasePort).release(ReleaseGateRequest(release.id))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.INVARIANT_VIOLATION
          store.written shouldBe null
          store.calls shouldBe listOf("begin", "snapshot", "rollback")
        }
      }
    })

private val human = Actor("human-1", ActorKind.HUMAN)

private fun useCase(
    store: RecordingGateStore,
    deployment: DeploymentPort = RecordingDeploymentPort(),
    release: ReleasePort = RecordingReleasePort(),
    compensation: CompensationPort = DeliveryRecordingCompensationPort(),
    featureFlagPort: FeatureFlagPort? = null,
): DeliveryGateUseCases =
    DeliveryGateUseCases(
        store,
        deployment,
        release,
        RecordingIdentityPort(human),
        idPort = RecordingIdPort(),
        clockPort = RecordingClockPort(),
        compensationPort = compensation,
        featureFlagPort = featureFlagPort,
    )

private fun storeFor(
    candidate: DeploymentCandidate,
    release: Release? = null,
    subTasks: List<SubTask> = emptyList(),
): RecordingGateStore =
    RecordingGateStore(
        WorkflowStoreSnapshot(
            "store-1",
            subTasks = subTasks,
            candidates = listOf(candidate),
            releases = listOfNotNull(release),
        )
    )

private fun deployCandidate(
    risk: Risk = Risk.HIGH,
    state: DeploymentCandidateState = DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL,
): DeploymentCandidate =
    DeploymentCandidate(
        id = "candidate-1",
        mainRevision = "main-1",
        includedSubTasks = listOf("sk-101"),
        risks = mapOf("sk-101" to risk),
        state = state,
    )

private fun featureFlagSubTask(id: String = "sk-101"): SubTask =
    SubTask(
        id = id,
        taskId = "task-1",
        title = "Feature Flag 배포 변경",
        exposure = Exposure.FEATURE_FLAG,
        featureFlagId = "recommendation-v2",
    )

private fun productionCandidate(): DeploymentCandidate =
    DeploymentCandidate(
        id = "candidate-1",
        mainRevision = "main-1",
        includedSubTasks = listOf("sk-101"),
        risks = mapOf("sk-101" to Risk.NORMAL),
        validations = listOf(Validation("validation-1", "test", ValidationStatus.PASSED)),
        state = DeploymentCandidateState.PRODUCTION,
    )

private fun release(
    productionReady: Boolean = true,
    internalValidationPassed: Boolean = true,
    state: ReleaseState = ReleaseState.AWAITING_RELEASE_APPROVAL,
): Release =
    Release(
        id = "release-1",
        candidateId = "candidate-1",
        featureFlagId = "recommendation-v2",
        state = state,
        productionReady = productionReady,
        internalValidationPassed = internalValidationPassed,
    )

private class RecordingIdentityPort(
    private val actor: Actor,
    private val allowed: Boolean = true,
) : IdentityPort {
  var authorizedCapability: Capability? = null

  override fun currentActor(request: CurrentActorRequest): PortResult<CurrentActorResponse> =
      PortResult.Success(CurrentActorResponse(actor))

  override fun authorize(request: AuthorizeRequest): PortResult<AuthorizeResponse> {
    authorizedCapability = request.capability
    return PortResult.Success(AuthorizeResponse(allowed))
  }
}

private class RecordingFeatureFlagPort(
    private val result: PortResult<ValidateFeatureFlagResponse>,
) : FeatureFlagPort {
  val calls = mutableListOf<String>()

  override fun validateDefault(
      request: ValidateFeatureFlagRequest
  ): PortResult<ValidateFeatureFlagResponse> {
    calls += request.featureFlagId
    return result
  }
}

private class RecordingDeploymentPort(
    private var candidate: DeploymentCandidate = deployCandidate(),
    private val compensatable: Boolean = false,
    private val startCandidate: DeploymentCandidate? = null,
) : DeploymentPort {
  var startCalls: Int = 0
  var startedActor: Actor? = null

  override fun createCandidate(
      request: CreateCandidateRequest
  ): PortResult<CreateCandidateResponse> = unsupported()

  override fun getCandidate(request: GetCandidateRequest): PortResult<GetCandidateResponse> =
      PortResult.Success(GetCandidateResponse(candidate))

  override fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse> =
      unsupported()

  override fun startCanary(request: StartCanaryRequest): PortResult<StartCanaryResponse> {
    startCalls += 1
    startedActor = request.actor
    candidate = startCandidate ?: candidate.copy(state = DeploymentCandidateState.CANARY)
    return PortResult.Success(
        StartCanaryResponse(candidate, change("start-canary", compensatable)),
    )
  }

  override fun promoteProduction(
      request: PromoteProductionRequest
  ): PortResult<PromoteProductionResponse> = unsupported()

  private fun <T> unsupported(): PortResult<T> =
      PortResult.Failure(PortError(FailureCode.STATE_CONFLICT.name, "지원하지 않는 테스트 동작입니다."))
}

private class RecordingReleasePort(
    private var release: Release? = null,
    private val startRelease: Release? = null,
) : ReleasePort {
  var startCalls: Int = 0
  var startedActor: Actor? = null

  override fun get(request: GetReleaseRequest): PortResult<GetReleaseResponse> =
      PortResult.Success(GetReleaseResponse(listOfNotNull(release)))

  override fun create(request: CreateReleaseRequest): PortResult<CreateReleaseResponse> =
      unsupported()

  override fun validateInternal(
      request: ValidateReleaseRequest
  ): PortResult<ValidateReleaseResponse> = unsupported()

  override fun start(request: StartReleaseRequest): PortResult<StartReleaseResponse> {
    startCalls += 1
    startedActor = request.actor
    val started = startRelease ?: release!!.copy(state = ReleaseState.ROLLOUT)
    release = started
    return PortResult.Success(StartReleaseResponse(started, change("start-release")))
  }

  override fun continueRollout(
      request: ContinueReleaseRequest
  ): PortResult<ContinueReleaseResponse> = unsupported()

  private fun <T> unsupported(): PortResult<T> =
      PortResult.Failure(PortError(FailureCode.STATE_CONFLICT.name, "지원하지 않는 테스트 동작입니다."))
}

private class RecordingGateStore(
    private var snapshot: WorkflowStoreSnapshot,
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
    return PortResult.Success(StoreSnapshotResponse(snapshot))
  }

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    calls += "write"
    if (failWrite) {
      return PortResult.Failure(
          PortError(FailureCode.STALE_REVISION.name, "저장소 revision이 변경되었습니다.")
      )
    }
    written = request.snapshot
    snapshot = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-2"))
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      PortResult.Failure(PortError(FailureCode.STATE_CONFLICT.name, "원자 쓰기 테스트에서는 호출하지 않습니다."))

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

private class DeliveryRecordingCompensationPort : CompensationPort {
  val operations = mutableListOf<String>()

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    operations += request.change.operation
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private class RecordingIdPort : IdPort {
  override fun issue(request: IssueIdRequest): PortResult<IssueIdResponse> =
      PortResult.Success(IssueIdResponse(listOf(IssuedId(request.kind, "audit-1"))))
}

private class RecordingClockPort : ClockPort {
  override fun now(request: NowRequest): PortResult<NowResponse> =
      PortResult.Success(NowResponse(1_000))
}

private fun change(operation: String, compensatable: Boolean = false): ChangeReceipt =
    ChangeReceipt(
        id = "change-$operation",
        operation = operation,
        compensation =
            if (compensatable) Compensation("comp-$operation", "undo-$operation", operation)
            else null,
    )
