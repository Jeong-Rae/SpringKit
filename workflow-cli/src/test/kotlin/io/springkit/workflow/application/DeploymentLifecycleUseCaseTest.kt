package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult

class DeploymentLifecycleUseCaseTest :
    FunSpec({
      context("normal 배포 후보를 만들면") {
        test("고정된 main revision과 SubTask를 보존하고 Canary를 자동으로 시작합니다") {
          val fixture = DeploymentLifecycleFixture()

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          val response = result.successData()
          response.candidate.state shouldBe DeploymentCandidateState.CANARY
          response.candidate.mainRevision shouldBe "main-1"
          response.candidate.includedSubTasks shouldContainExactly listOf("sk-101")
          response.candidate.risks shouldBe mapOf("sk-101" to Risk.NORMAL)
          fixture.deployment.startCanaryCalls shouldBe 1
        }
      }

      context("high 배포 후보를 만들면") {
        test("필수 validation 뒤 AWAITING_DEPLOY_APPROVAL 상태로 대기합니다") {
          val fixture = DeploymentLifecycleFixture(risk = Risk.HIGH)

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          result.successData().candidate.state shouldBe
              DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL
          fixture.deployment.startCanaryCalls shouldBe 0
        }
      }

      context("Canary 완료 결과를 반영하면") {
        test("성공 시 같은 candidate를 Production으로 승격하고 다시 받아도 멱등 처리합니다") {
          val fixture = DeploymentLifecycleFixture()
          val created = fixture.useCase().create(DeploymentLifecycleRequest("main-1")).successData()
          val request =
              DeploymentCanaryEventRequest(
                  candidateId = created.candidate.id,
                  outcome = DeploymentCanaryOutcome.SUCCEEDED,
                  eventId = "canary-event-1",
              )

          val promoted = fixture.useCase().handle(request).successData()
          val repeated = fixture.useCase().handle(request).successData()

          promoted.candidate.id shouldBe created.candidate.id
          promoted.candidate.state shouldBe DeploymentCandidateState.PRODUCTION
          repeated.idempotent shouldBe true
          fixture.deployment.promoteCalls shouldBe 1
        }

        test("실패 시 candidate를 FAILED로 저장하고 같은 결과를 멱등 처리합니다") {
          val fixture = DeploymentLifecycleFixture()
          val created = fixture.useCase().create(DeploymentLifecycleRequest("main-1")).successData()
          val request =
              DeploymentCanaryEventRequest(
                  candidateId = created.candidate.id,
                  outcome = DeploymentCanaryOutcome.FAILED,
                  eventId = "canary-event-2",
              )

          val failed = fixture.useCase().handle(request).successData()
          val repeated = fixture.useCase().handle(request).successData()

          failed.candidate.state shouldBe DeploymentCandidateState.FAILED
          failed.failureStage shouldBe DeploymentFailureStage.CANARY
          repeated.idempotent shouldBe true
          fixture.store.current.candidates.single().state shouldBe DeploymentCandidateState.FAILED
        }
      }

      context("provider 응답이 candidate identity와 다르면") {
        test("상태를 저장하지 않고 provider 변경을 보상합니다") {
          val fixture = DeploymentLifecycleFixture(validateWithDifferentRevision = true)

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          result.failureData().code shouldBe
              io.springkit.workflow.domain.FailureCode.INVARIANT_VIOLATION
          fixture.store.writes shouldBe 0
          fixture.store.rollbacks shouldBe 1
          fixture.compensation.calls shouldBe 2
        }
      }

      context("Store transaction 중 provider 동작이 실패하면") {
        test("rollback 후 이미 적용된 변경을 역순으로 보상합니다") {
          val fixture = DeploymentLifecycleFixture(validationFailure = true)

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          result.failureData().code shouldBe
              io.springkit.workflow.domain.FailureCode.EXTERNAL_FAILURE
          fixture.store.writes shouldBe 0
          fixture.store.rollbacks shouldBe 1
          fixture.compensation.calls shouldBe 2
        }
      }
    })

private fun WorkflowResult<DeploymentLifecycleResponse>.successData(): DeploymentLifecycleResponse =
    shouldBeInstanceOf<WorkflowResult.Success<DeploymentLifecycleResponse>>().data

private fun WorkflowResult<DeploymentLifecycleResponse>.failureData() =
    shouldBeInstanceOf<WorkflowResult.Failure>().data

private class DeploymentLifecycleFixture(
    risk: Risk = Risk.NORMAL,
    private val validationFailure: Boolean = false,
    private val validateWithDifferentRevision: Boolean = false,
) {
  val store = DeploymentStore()
  val compensation = DeploymentCompensationPort()
  val deployment =
      DeploymentProvider(
          risk = risk,
          validationFailure = validationFailure,
          validateWithDifferentRevision = validateWithDifferentRevision,
      )

  init {
    store.current =
        WorkflowStoreSnapshot(
            revision = "store-1",
            subTasks = listOf(SubTask("sk-101", "task-1", "배포 변경", risk = risk)),
            integrations =
                listOf(Integration("sk-101", IntegrationState.MERGED, mainRevision = "main-1")),
        )
  }

  fun useCase() =
      DeploymentLifecycleUseCase(
          storePort = store,
          deploymentPort = deployment,
          compensationPort = compensation,
      )
}

private class DeploymentStore : WorkflowStorePort {
  lateinit var current: WorkflowStoreSnapshot
  var writes = 0
  var rollbacks = 0

  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> =
      PortResult.Success(StoreSnapshotResponse(current))

  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN)
      )

  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    writes += 1
    current = request.snapshot
    return PortResult.Success(StoreWriteResponse("store-${writes + 1}"))
  }

  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> =
      PortResult.Success(
          StoreTransactionResponse(request.transactionId, StoreTransactionState.COMMITTED)
      )

  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    rollbacks += 1
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.ROLLED_BACK)
    )
  }

  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> =
      PortResult.Success(StoreEventResponse(request.event, current.revision))
}

private class DeploymentCompensationPort : CompensationPort {
  var calls = 0

  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> {
    calls += 1
    return PortResult.Success(CompensateResponse(request.change))
  }
}

private class DeploymentProvider(
    private val risk: Risk,
    private val validationFailure: Boolean,
    private val validateWithDifferentRevision: Boolean,
) : DeploymentPort {
  var startCanaryCalls = 0
  var promoteCalls = 0
  private var candidate =
      DeploymentCandidate("candidate-1", "main-1", listOf("sk-101"), mapOf("sk-101" to risk))

  override fun createCandidate(
      request: CreateCandidateRequest
  ): PortResult<CreateCandidateResponse> {
    candidate =
        DeploymentCandidate(
            "candidate-1",
            request.mainRevision,
            request.includedSubTasks,
            request.risks,
        )
    return PortResult.Success(
        CreateCandidateResponse(candidate, change("create-candidate")),
    )
  }

  override fun getCandidate(request: GetCandidateRequest): PortResult<GetCandidateResponse> =
      PortResult.Success(GetCandidateResponse(candidate))

  override fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse> {
    if (validationFailure) {
      return PortResult.Failure(
          PortError("EXTERNAL_FAILURE", "provider validation failed"),
          change("validate-candidate"),
      )
    }
    candidate =
        candidate.copy(
            mainRevision =
                if (validateWithDifferentRevision) "other-main" else candidate.mainRevision,
            validations = listOf(Validation("validation-1", "필수 검증", ValidationStatus.PASSED)),
        )
    return PortResult.Success(
        ValidateCandidateResponse(candidate, change("validate-candidate")),
    )
  }

  override fun startCanary(request: StartCanaryRequest): PortResult<StartCanaryResponse> {
    startCanaryCalls += 1
    candidate = candidate.copy(state = DeploymentCandidateState.CANARY)
    return PortResult.Success(StartCanaryResponse(candidate, change("start-canary")))
  }

  override fun promoteProduction(
      request: PromoteProductionRequest
  ): PortResult<PromoteProductionResponse> {
    promoteCalls += 1
    candidate = candidate.copy(state = DeploymentCandidateState.PRODUCTION)
    return PortResult.Success(PromoteProductionResponse(candidate, change("promote-production")))
  }

  private fun change(operation: String) =
      ChangeReceipt(
          id = operation,
          operation = operation,
          compensation = Compensation("comp-$operation", operation, "key-$operation"),
      )
}
