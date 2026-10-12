package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.EventLog
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeRecorded
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult

class DeploymentLifecycleUseCaseTest :
    FunSpec({
      context("normal 배포 후보를 만들면") {
        test("normal 배포 후보를 만들면, 고정된 main revision과 SubTask를 보존하고 Canary를 자동으로 시작합니다") {
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

      context("Production 이후의 main revision으로 후보를 만들면") {
        test("Production 이후 main revision으로 후보를 만들면, merge된 모든 SubTask를 merge 순서대로 누적합니다") {
          val fixture = DeploymentLifecycleFixture()
          val secondSubTask = SubTask("sk-102", "task-1", "두 번째 배포 변경")
          val thirdSubTask = SubTask("sk-103", "task-1", "세 번째 배포 변경")
          fixture.store.current =
              fixture.store.current.copy(
                  subTasks = fixture.store.current.subTasks + secondSubTask + thirdSubTask,
                  integrations =
                      listOf(
                          Integration("sk-101", IntegrationState.MERGED, mainRevision = "main-1"),
                          Integration("sk-102", IntegrationState.MERGED, mainRevision = "main-2"),
                          Integration("sk-103", IntegrationState.MERGED, mainRevision = "main-3"),
                      ),
                  candidates =
                      listOf(
                          DeploymentCandidate(
                              id = "candidate-production",
                              mainRevision = "main-1",
                              includedSubTasks = listOf("sk-101"),
                              risks = mapOf("sk-101" to Risk.NORMAL),
                              validations =
                                  listOf(
                                      Validation(
                                          "validation-production",
                                          "필수 검증",
                                          ValidationStatus.PASSED,
                                      )
                                  ),
                              state = DeploymentCandidateState.PRODUCTION,
                          )
                      ),
                  eventLog =
                      EventLog(
                          events =
                              listOf(
                                  MergeRecorded("sk-101", "main-1"),
                                  MergeRecorded("sk-102", "main-2"),
                                  MergeRecorded("sk-103", "main-3"),
                              )
                      ),
              )

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-3"))

          val candidate = result.successData().candidate
          candidate.mainRevision shouldBe "main-3"
          candidate.includedSubTasks shouldContainExactly listOf("sk-102", "sk-103")
          candidate.risks shouldBe
              mapOf(
                  "sk-102" to Risk.NORMAL,
                  "sk-103" to Risk.NORMAL,
              )
          fixture.deployment.createCandidateCalls shouldBe 1
        }

        test("활성 후보가 있으면, 추가 merge revision이 기존 후보를 변경하지 않습니다") {
          val fixture = DeploymentLifecycleFixture()
          val secondSubTask = SubTask("sk-102", "task-1", "두 번째 배포 변경")
          fixture.store.current =
              fixture.store.current.copy(
                  subTasks = fixture.store.current.subTasks + secondSubTask,
                  integrations =
                      listOf(
                          Integration("sk-101", IntegrationState.MERGED, mainRevision = "main-1"),
                          Integration("sk-102", IntegrationState.MERGED, mainRevision = "main-2"),
                      ),
                  eventLog =
                      EventLog(
                          events =
                              listOf(
                                  MergeRecorded("sk-101", "main-1"),
                                  MergeRecorded("sk-102", "main-2"),
                              )
                      ),
              )
          fixture.prepareCandidate(DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL)

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-2"))

          val candidate = result.successData()
          candidate.idempotent shouldBe true
          candidate.candidate.mainRevision shouldBe "main-1"
          result
              .shouldBeInstanceOf<WorkflowResult.Success<DeploymentLifecycleResponse>>()
              .next
              .single()
              .action shouldBe "approve_deployment"
          candidate.candidate.includedSubTasks shouldContainExactly listOf("sk-101")
          fixture.deployment.createCandidateCalls shouldBe 0
          fixture.store.current.candidates.single().mainRevision shouldBe "main-1"
        }

        test("현재 Production보다 이전인 target revision이면, 상태 충돌로 차단합니다") {
          val fixture = DeploymentLifecycleFixture()
          val secondSubTask = SubTask("sk-102", "task-1", "두 번째 배포 변경")
          fixture.store.current =
              fixture.store.current.copy(
                  subTasks = fixture.store.current.subTasks + secondSubTask,
                  integrations =
                      listOf(
                          Integration("sk-101", IntegrationState.MERGED, mainRevision = "main-1"),
                          Integration("sk-102", IntegrationState.MERGED, mainRevision = "main-2"),
                      ),
                  candidates =
                      listOf(
                          DeploymentCandidate(
                              id = "candidate-old",
                              mainRevision = "main-1",
                              includedSubTasks = listOf("sk-101"),
                              risks = mapOf("sk-101" to Risk.NORMAL),
                              state = DeploymentCandidateState.FAILED,
                          ),
                          DeploymentCandidate(
                              id = "candidate-production",
                              mainRevision = "main-2",
                              includedSubTasks = listOf("sk-101", "sk-102"),
                              risks =
                                  mapOf(
                                      "sk-101" to Risk.NORMAL,
                                      "sk-102" to Risk.NORMAL,
                                  ),
                              validations =
                                  listOf(
                                      Validation(
                                          "validation-production",
                                          "필수 검증",
                                          ValidationStatus.PASSED,
                                      )
                                  ),
                              state = DeploymentCandidateState.PRODUCTION,
                          ),
                      ),
                  eventLog =
                      EventLog(
                          events =
                              listOf(
                                  MergeRecorded("sk-101", "main-1"),
                                  MergeRecorded("sk-102", "main-2"),
                              )
                      ),
              )

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          result.failureData().code shouldBe io.springkit.workflow.domain.FailureCode.STATE_CONFLICT
          fixture.deployment.createCandidateCalls shouldBe 0
        }
      }

      context("high 배포 후보를 만들면") {
        test("high 배포 후보를 만들면, 필수 validation 뒤 AWAITING_DEPLOY_APPROVAL 상태로 대기합니다") {
          val fixture = DeploymentLifecycleFixture(risk = Risk.HIGH)

          val result = fixture.useCase().create(DeploymentLifecycleRequest("main-1"))

          result.successData().candidate.state shouldBe
              DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL
          fixture.deployment.startCanaryCalls shouldBe 0
        }
      }

      context("DEPLOYMENT_CHANGED 이벤트를 받으면") {
        test("DEPLOYMENT_CHANGED 이벤트를 수신하면, 필수 validation을 통과한 normal 후보를 Canary로 진행합니다") {
          val fixture = DeploymentLifecycleFixture()
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-1"))

          result.successData().candidate.state shouldBe DeploymentCandidateState.CANARY
          fixture.deployment.getCandidateCalls shouldBe 1
          fixture.deployment.validateCalls shouldBe 1
          fixture.deployment.startCanaryCalls shouldBe 1
        }

        test("high 후보의 필수 validation이 통과하면, 배포 승인 대기로 전환합니다") {
          val fixture = DeploymentLifecycleFixture(risk = Risk.HIGH)
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-2"))

          result.successData().candidate.state shouldBe
              DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL
          fixture.deployment.startCanaryCalls shouldBe 0
        }

        test("필수 validation이 끝나지 않았으면, VALIDATING 상태를 유지합니다") {
          val fixture = DeploymentLifecycleFixture()
          fixture.deployment.setValidationResult(
              listOf(Validation("validation-1", "필수 검증", ValidationStatus.PENDING))
          )
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-3"))

          result.successData().candidate.state shouldBe DeploymentCandidateState.VALIDATING
          fixture.deployment.startCanaryCalls shouldBe 0
        }

        test("같은 event id를 다시 받으면, provider를 호출하지 않고 멱등 처리합니다") {
          val fixture = DeploymentLifecycleFixture()
          fixture.deployment.setValidationResult(
              listOf(Validation("validation-1", "필수 검증", ValidationStatus.PENDING))
          )
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)
          val event = deploymentChangedEvent("deployment-event-duplicate")

          fixture.useCase().handle(event).successData()
          val repeated = fixture.useCase().handle(event).successData()

          repeated.idempotent shouldBe true
          fixture.deployment.getCandidateCalls shouldBe 1
          fixture.deployment.validateCalls shouldBe 1
        }

        test("필수 validation이 실패하면, FAILED 상태로 저장합니다") {
          val fixture = DeploymentLifecycleFixture()
          fixture.deployment.setValidationResult(
              listOf(
                  Validation(
                      "validation-1",
                      "필수 검증",
                      ValidationStatus.FAILED,
                      message = "검증 실패",
                  )
              )
          )
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-4"))

          val response = result.successData()
          response.candidate.state shouldBe DeploymentCandidateState.FAILED
          response.failureStage shouldBe DeploymentFailureStage.VALIDATION
        }

        test("이미 더 진행된 후보이면, provider를 다시 호출하지 않고 멱등 성공합니다") {
          val fixture = DeploymentLifecycleFixture()
          fixture.prepareCandidate(DeploymentCandidateState.CANARY)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-5"))

          result.successData().idempotent shouldBe true
          fixture.deployment.getCandidateCalls shouldBe 0
          fixture.deployment.validateCalls shouldBe 0
          fixture.deployment.startCanaryCalls shouldBe 0
        }

        test("provider identity가 달라지면, 후보를 역행하지 않고 불변식 오류를 반환합니다") {
          val fixture = DeploymentLifecycleFixture(validateWithDifferentRevision = true)
          fixture.prepareCandidate(DeploymentCandidateState.VALIDATING)

          val result = fixture.useCase().handle(deploymentChangedEvent("deployment-event-6"))

          result.failureData().code shouldBe
              io.springkit.workflow.domain.FailureCode.INVARIANT_VIOLATION
          fixture.store.writes shouldBe 0
          fixture.store.rollbacks shouldBe 1
          fixture.compensation.calls shouldBe 1
        }
      }

      context("Canary 완료 결과를 반영하면") {
        test("Canary가 성공하면, 같은 candidate를 Production으로 승격하고 다시 받아도 멱등 처리합니다") {
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

        test("Canary가 실패하면, candidate를 FAILED로 저장하고 같은 결과를 멱등 처리합니다") {
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
        test("provider 응답이 candidate identity와 다르면, 상태를 저장하지 않고 provider 변경을 보상합니다") {
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
        test("Store transaction 중 provider 동작이 실패하면, rollback 후 이미 적용된 변경을 역순으로 보상합니다") {
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

private fun deploymentChangedEvent(id: String): ExternalEvent =
    ExternalEvent(
        id = id,
        kind = ExternalEventKind.DEPLOYMENT_CHANGED,
        targetId = "candidate-1",
        occurredAtEpochMillis = 1,
    )

private fun WorkflowResult<DeploymentLifecycleResponse>.successData(): DeploymentLifecycleResponse =
    shouldBeInstanceOf<WorkflowResult.Success<DeploymentLifecycleResponse>>().data

private fun WorkflowResult<DeploymentLifecycleResponse>.failureData() =
    shouldBeInstanceOf<WorkflowResult.Failure>().data

private class DeploymentLifecycleFixture(
    private val risk: Risk = Risk.NORMAL,
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

  fun prepareCandidate(state: DeploymentCandidateState) {
    val candidate =
        DeploymentCandidate(
            id = "candidate-1",
            mainRevision = "main-1",
            includedSubTasks = listOf("sk-101"),
            risks = mapOf("sk-101" to risk),
            state = state,
            validations = emptyList(),
        )
    store.current = store.current.copy(candidates = listOf(candidate))
    deployment.setCandidate(candidate)
  }
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
  var createCandidateCalls = 0
  var getCandidateCalls = 0
  var validateCalls = 0
  var startCanaryCalls = 0
  var promoteCalls = 0
  private var validationResult: List<Validation>? = null
  private var candidate =
      DeploymentCandidate("candidate-1", "main-1", listOf("sk-101"), mapOf("sk-101" to risk))

  override fun createCandidate(
      request: CreateCandidateRequest
  ): PortResult<CreateCandidateResponse> {
    createCandidateCalls += 1
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
      PortResult.Success(GetCandidateResponse(candidate)).also { getCandidateCalls += 1 }

  override fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse> {
    validateCalls += 1
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
            validations =
                validationResult
                    ?: listOf(Validation("validation-1", "필수 검증", ValidationStatus.PASSED)),
        )
    return PortResult.Success(
        ValidateCandidateResponse(candidate, change("validate-candidate")),
    )
  }

  fun setCandidate(value: DeploymentCandidate) {
    candidate = value
  }

  fun setValidationResult(value: List<Validation>) {
    validationResult = value
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
