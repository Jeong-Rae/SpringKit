package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.DeploymentRecorded
import io.springkit.workflow.domain.EventLog
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MainRevision
import io.springkit.workflow.domain.MergeRecorded
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.requiredValidationsPassed

/*
 * 병합된 main revision을 후보로 만들고 Production까지 진행하는 요청입니다.
 */
data class DeploymentLifecycleRequest(
    val mainRevision: MainRevision,
    val requestId: String = "candidate-$mainRevision",
    val expectedStoreRevision: String? = null,
) {
  init {
    require(mainRevision.isNotBlank()) { "deployment main revision must not be blank" }
    require(requestId.isNotBlank()) { "deployment request id must not be blank" }
  }
}

/*
 * Canary 결과를 Workflow에 반영하는 요청입니다.
 */
data class DeploymentCanaryEventRequest(
    val candidateId: CandidateId,
    val outcome: DeploymentCanaryOutcome,
    val eventId: String,
    val requestId: String = "canary-$eventId",
) {
  init {
    require(candidateId.isNotBlank()) { "canary candidate id must not be blank" }
    require(eventId.isNotBlank()) { "canary event id must not be blank" }
    require(requestId.isNotBlank()) { "canary request id must not be blank" }
  }
}

/*
 * Canary가 최종적으로 보고한 결과입니다.
 */
enum class DeploymentCanaryOutcome {
  SUCCEEDED,
  FAILED,
}

/*
 * 후보 검증 또는 Canary에서 발생한 실패 단계를 구분합니다.
 */
enum class DeploymentFailureStage {
  VALIDATION,
  CANARY,
}

/*
 * 배포 lifecycle의 상태와 실패 단계를 반환합니다.
 */
data class DeploymentLifecycleResponse(
    val candidate: DeploymentCandidate,
    val failureStage: DeploymentFailureStage? = null,
    val idempotent: Boolean = false,
)

typealias DeploymentCandidateRequest = DeploymentLifecycleRequest

typealias DeploymentCandidateResponse = DeploymentLifecycleResponse

typealias CanaryEventRequest = DeploymentCanaryEventRequest

/*
 * merge된 main revision을 고정하고 후보 검증, Canary, Production 전이를 조정합니다.
 */
class DeploymentLifecycleUseCase(
    private val storePort: WorkflowStorePort,
    private val deploymentPort: DeploymentPort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
) {
  /*
   * merge된 SubTask로 배포 후보를 만들고 검증을 시작합니다.
   */
  fun create(request: DeploymentLifecycleRequest): WorkflowResult<DeploymentLifecycleResponse> {
    val initial = readSnapshot(request.expectedStoreRevision, request.mainRevision)
    if (initial is SnapshotRead.Failure) return initial.failure
    val snapshot = (initial as SnapshotRead.Success).snapshot
    val merged = mergedSubTasks(snapshot, request.mainRevision)
    if (merged is MergedSubTasks.Failure) return merged.failure
    snapshot.candidates
        .firstOrNull { it.mainRevision == request.mainRevision }
        ?.let {
          return WorkflowResult.Success(
              DeploymentLifecycleResponse(it, idempotent = true),
              nextActions(it),
          )
        }
    val (subTasks, risks) = (merged as MergedSubTasks.Success).value
    snapshot.candidates
        .firstOrNull { it.state.isActiveDeployment() }
        ?.let {
          return WorkflowResult.Success(
              DeploymentLifecycleResponse(it, idempotent = true),
              nextActions(it),
          )
        }
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = issueTransactionId(request.requestId),
            expectedRevision = snapshot.revision,
            idempotencyKey = request.requestId,
        )

    return transaction(transactionRequest) { current ->
          val currentMerged = mergedSubTasks(current, request.mainRevision)
          if (currentMerged is MergedSubTasks.Failure)
              return@transaction Operation.Abort(currentMerged.failure.toPortFailure())
          current.candidates
              .firstOrNull { it.mainRevision == request.mainRevision }
              ?.let {
                return@transaction Operation.Commit(
                    TransactionMutation(
                        DeploymentLifecycleResponse(it, idempotent = true),
                        current,
                    )
                )
              }
          val (currentSubTasks, currentRisks) = (currentMerged as MergedSubTasks.Success).value
          current.candidates
              .firstOrNull { it.state.isActiveDeployment() }
              ?.let {
                return@transaction Operation.Commit(
                    TransactionMutation(
                        DeploymentLifecycleResponse(it, idempotent = true),
                        current,
                    )
                )
              }
          if (currentSubTasks.map { it.id } != subTasks.map { it.id } || currentRisks != risks) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.STALE_REVISION,
                    "merged subtasks changed while creating the deployment candidate",
                    request.mainRevision,
                )
            )
          }

          val created =
              when (
                  val result =
                      deploymentPort.createCandidate(
                          CreateCandidateRequest(
                              mainRevision = request.mainRevision,
                              includedSubTasks = currentSubTasks.map(SubTask::id),
                              risks = currentRisks,
                          )
                      )
              ) {
                is PortResult.Failure -> return@transaction Operation.Abort(result)
                is PortResult.Success -> result.value
              }
          val candidate = created.candidate
          validateCandidateIdentity(
                  candidate,
                  request.mainRevision,
                  currentSubTasks.map(SubTask::id),
                  currentRisks,
                  expectedCandidateId = null,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, candidate.id, created.change),
                    listOf(created.change),
                )
              }
          if (candidate.state != DeploymentCandidateState.CANDIDATE) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.INVARIANT_VIOLATION,
                    "deployment provider did not create a candidate",
                    candidate.id,
                    created.change,
                )
            )
          }

          val validated =
              when (
                  val result =
                      deploymentPort.validate(
                          ValidateCandidateRequest(candidate.id, request.mainRevision)
                      )
              ) {
                is PortResult.Failure ->
                    return@transaction Operation.Abort(
                        result.withChange(created.change),
                        listOf(created.change),
                    )
                is PortResult.Success -> result.value
              }
          validateCandidateIdentity(
                  validated.candidate,
                  request.mainRevision,
                  candidate.includedSubTasks,
                  candidate.risks,
                  expectedCandidateId = candidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, candidate.id, validated.change),
                    listOf(created.change, validated.change),
                )
              }
          val validationCandidate = validated.candidate
          val changes = listOf(created.change, validated.change)
          val validationFailed =
              validationCandidate.validations.any {
                it.required && it.status == ValidationStatus.FAILED
              }
          val validationPassed =
              validationCandidate.validations.isNotEmpty() &&
                  requiredValidationsPassed(validationCandidate.validations)
          if (validationFailed) {
            val failed = validationCandidate.copy(state = DeploymentCandidateState.FAILED)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(failed, DeploymentFailureStage.VALIDATION),
                    current.copy(
                        candidates = current.candidates + failed,
                        eventLog =
                            current.eventLog.append(DeploymentRecorded(failed.id, failed.state)),
                    ),
                    changes,
                )
            )
          }
          if (!validationPassed) {
            val validating = validationCandidate.copy(state = DeploymentCandidateState.VALIDATING)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(validating),
                    current.copy(
                        candidates = current.candidates + validating,
                        eventLog =
                            current.eventLog.append(
                                DeploymentRecorded(validating.id, validating.state)
                            ),
                    ),
                    changes,
                )
            )
          }

          if (candidate.risk == Risk.HIGH) {
            val awaiting =
                validationCandidate.copy(state = DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(awaiting),
                    current.copy(
                        candidates = current.candidates + awaiting,
                        eventLog =
                            current.eventLog.append(
                                DeploymentRecorded(awaiting.id, awaiting.state)
                            ),
                    ),
                    changes,
                )
            )
          }

          val canary =
              when (val result = deploymentPort.startCanary(StartCanaryRequest(candidate.id))) {
                is PortResult.Failure ->
                    return@transaction Operation.Abort(
                        result.withChange(created.change),
                        listOf(created.change, validated.change),
                    )
                is PortResult.Success -> result.value
              }
          validateCandidateIdentity(
                  canary.candidate,
                  request.mainRevision,
                  candidate.includedSubTasks,
                  candidate.risks,
                  expectedCandidateId = candidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, candidate.id, canary.change),
                    listOf(created.change, validated.change, canary.change),
                )
              }
          if (canary.candidate.state != DeploymentCandidateState.CANARY) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.INVARIANT_VIOLATION,
                    "deployment provider did not start the candidate canary",
                    candidate.id,
                    canary.change,
                )
            )
          }
          val updated =
              canary.candidate.copy(
                  state = DeploymentCandidateState.CANARY,
              )
          Operation.Commit(
              TransactionMutation(
                  DeploymentLifecycleResponse(updated),
                  current.copy(
                      candidates = current.candidates + updated,
                      eventLog =
                          current.eventLog.append(DeploymentRecorded(updated.id, updated.state)),
                  ),
                  changes + canary.change,
              )
          )
        }
        .mapFailureStage()
  }

  /*
   * 배포 후보 생성 명령의 공통 진입점입니다.
   */
  fun execute(request: DeploymentLifecycleRequest): WorkflowResult<DeploymentLifecycleResponse> =
      create(request)

  /*
   * 배포 후보 생성 명령을 인바운드 어댑터에서 호출합니다.
   */
  fun handle(request: DeploymentLifecycleRequest): WorkflowResult<DeploymentLifecycleResponse> =
      create(request)

  /*
   * 외부 Canary 변경 이벤트를 처리합니다.
   */
  fun handle(event: ExternalEvent): WorkflowResult<DeploymentLifecycleResponse> {
    if (event.kind == ExternalEventKind.DEPLOYMENT_CHANGED) {
      return handleDeploymentChanged(event)
    }
    if (event.kind != ExternalEventKind.CANARY_CHANGED) {
      return failure(
          FailureCode.INVALID_ARGUMENT,
          "only DEPLOYMENT_CHANGED and CANARY_CHANGED events can advance a deployment candidate",
          event.targetId,
      )
    }
    val outcome =
        when (
            event.attributes["outcome"]?.uppercase()
                ?: event.attributes["result"]?.uppercase()
                ?: event.attributes["status"]?.uppercase()
        ) {
          "SUCCESS",
          "SUCCEEDED",
          "PASSED",
          "HEALTHY",
          "COMPLETED",
          -> DeploymentCanaryOutcome.SUCCEEDED
          "FAILURE",
          "FAILED",
          "ERROR",
          "UNHEALTHY",
          -> DeploymentCanaryOutcome.FAILED
          else ->
              return failure(
                  FailureCode.INVALID_ARGUMENT,
                  "CANARY_CHANGED event must contain a recognized outcome",
                  event.targetId,
              )
        }
    return handle(
        DeploymentCanaryEventRequest(
            candidateId = event.targetId,
            outcome = outcome,
            eventId = event.id,
            requestId = "canary-${event.id}",
        )
    )
  }

  /*
   * 명시적인 Canary 결과를 처리합니다.
   */
  fun handle(request: DeploymentCanaryEventRequest): WorkflowResult<DeploymentLifecycleResponse> {
    val initial = readSnapshot(null, request.candidateId)
    if (initial is SnapshotRead.Failure) return initial.failure
    val snapshot = (initial as SnapshotRead.Success).snapshot
    val current =
        snapshot.candidates.firstOrNull { it.id == request.candidateId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                request.candidateId,
            )
    if (
        request.outcome == DeploymentCanaryOutcome.SUCCEEDED &&
            current.state == DeploymentCandidateState.PRODUCTION
    ) {
      return WorkflowResult.Success(DeploymentLifecycleResponse(current, idempotent = true))
    }
    if (
        request.outcome == DeploymentCanaryOutcome.FAILED &&
            current.state == DeploymentCandidateState.FAILED
    ) {
      return WorkflowResult.Success(
          DeploymentLifecycleResponse(
              current,
              failureStage = DeploymentFailureStage.CANARY,
              idempotent = true,
          )
      )
    }
    if (current.state != DeploymentCandidateState.CANARY) {
      return failure(
          FailureCode.STATE_CONFLICT,
          "canary event requires a candidate in CANARY state",
          request.candidateId,
      )
    }
    val transactionRequest =
        StoreTransactionRequest(
            transactionId = issueTransactionId(request.requestId),
            expectedRevision = snapshot.revision,
            idempotencyKey = request.eventId,
        )
    return transaction(transactionRequest) { latest ->
          val candidate =
              latest.candidates.firstOrNull { it.id == request.candidateId }
                  ?: return@transaction Operation.Abort(
                      failurePort(
                          FailureCode.DEPLOYMENT_NOT_FOUND,
                          "deployment candidate was not found",
                          request.candidateId,
                      )
                  )
          if (request.outcome == DeploymentCanaryOutcome.FAILED) {
            val failed = candidate.copy(state = DeploymentCandidateState.FAILED)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(failed, DeploymentFailureStage.CANARY),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(failed),
                        eventLog =
                            latest.eventLog.append(DeploymentRecorded(failed.id, failed.state)),
                    ),
                )
            )
          }
          val promoted =
              when (
                  val result =
                      deploymentPort.promoteProduction(
                          PromoteProductionRequest(candidate.id, DeploymentCandidateState.CANARY)
                      )
              ) {
                is PortResult.Failure -> return@transaction Operation.Abort(result)
                is PortResult.Success -> result.value
              }
          validateCandidateIdentity(
                  promoted.candidate,
                  candidate.mainRevision,
                  candidate.includedSubTasks,
                  candidate.risks,
                  expectedCandidateId = candidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, candidate.id, promoted.change),
                    listOf(promoted.change),
                )
              }
          if (promoted.candidate.state != DeploymentCandidateState.PRODUCTION) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.INVARIANT_VIOLATION,
                    "deployment provider did not promote the canary to production",
                    candidate.id,
                    promoted.change,
                )
            )
          }
          Operation.Commit(
              TransactionMutation(
                  DeploymentLifecycleResponse(promoted.candidate),
                  latest.copy(
                      candidates = latest.candidates.replaceCandidate(promoted.candidate),
                      eventLog =
                          latest.eventLog.append(
                              DeploymentRecorded(promoted.candidate.id, promoted.candidate.state)
                          ),
                  ),
                  listOf(promoted.change),
              )
          )
        }
        .mapFailureStage()
  }

  /*
   * 외부 Deployment 변경 이벤트를 재조회하고 검증 결과에 따라 안전하게 진행합니다.
   */
  private fun handleDeploymentChanged(
      event: ExternalEvent
  ): WorkflowResult<DeploymentLifecycleResponse> {
    val initial = readSnapshot(null, event.targetId)
    if (initial is SnapshotRead.Failure) return initial.failure
    val snapshot = (initial as SnapshotRead.Success).snapshot
    val current =
        snapshot.candidates.firstOrNull { it.id == event.targetId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                event.targetId,
            )
    if (snapshot.eventLog.audits.any { it.id == event.id }) {
      return WorkflowResult.Success(
          DeploymentLifecycleResponse(current, idempotent = true),
          nextActions(current),
      )
    }
    if (current.state.isAfterValidation()) {
      return WorkflowResult.Success(
          DeploymentLifecycleResponse(current, idempotent = true),
          nextActions(current),
      )
    }
    if (current.state == DeploymentCandidateState.FAILED) {
      return WorkflowResult.Success(
          DeploymentLifecycleResponse(current, DeploymentFailureStage.VALIDATION, true)
      )
    }
    if (
        current.state !in
            setOf(DeploymentCandidateState.CANDIDATE, DeploymentCandidateState.VALIDATING)
    ) {
      return failure(
          FailureCode.STATE_CONFLICT,
          "deployment changed event cannot advance the candidate from its current state",
          current.id,
      )
    }

    val transactionRequest =
        StoreTransactionRequest(
            transactionId = issueTransactionId(event.id),
            expectedRevision = snapshot.revision,
            idempotencyKey = event.id,
        )
    return transaction(transactionRequest) { latest ->
          val latestCandidate =
              latest.candidates.firstOrNull { it.id == event.targetId }
                  ?: return@transaction Operation.Abort(
                      failurePort(
                          FailureCode.DEPLOYMENT_NOT_FOUND,
                          "deployment candidate was not found",
                          event.targetId,
                      )
                  )
          if (latest.eventLog.audits.any { it.id == event.id }) {
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(latestCandidate, idempotent = true),
                    latest,
                )
            )
          }
          if (latestCandidate.state.isAfterValidation()) {
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(latestCandidate, idempotent = true),
                    latest,
                )
            )
          }
          if (latestCandidate.state == DeploymentCandidateState.FAILED) {
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(
                        latestCandidate,
                        DeploymentFailureStage.VALIDATION,
                        idempotent = true,
                    ),
                    latest,
                )
            )
          }
          if (
              latestCandidate.state !in
                  setOf(DeploymentCandidateState.CANDIDATE, DeploymentCandidateState.VALIDATING)
          ) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.STATE_CONFLICT,
                    "deployment changed event cannot advance the candidate from its current state",
                    latestCandidate.id,
                )
            )
          }

          val observed =
              when (val result = deploymentPort.getCandidate(GetCandidateRequest(event.targetId))) {
                is PortResult.Failure -> return@transaction Operation.Abort(result)
                is PortResult.Success -> result.value
              }
          validateCandidateIdentity(
                  observed.candidate,
                  latestCandidate.mainRevision,
                  latestCandidate.includedSubTasks,
                  latestCandidate.risks,
                  expectedCandidateId = latestCandidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, latestCandidate.id),
                )
              }

          val validation =
              if (
                  observed.candidate.state == DeploymentCandidateState.FAILED ||
                      observed.candidate.state.isAfterValidation() ||
                      !observed.candidate.requiresValidationRefresh()
              ) {
                observed.candidate to null
              } else {
                when (
                    val result =
                        deploymentPort.validate(
                            ValidateCandidateRequest(
                                latestCandidate.id,
                                latestCandidate.mainRevision,
                            )
                        )
                ) {
                  is PortResult.Failure -> return@transaction Operation.Abort(result)
                  is PortResult.Success -> result.value.candidate to result.value.change
                }
              }
          val validatedCandidate = validation.first
          val validationChange = validation.second
          validateCandidateIdentity(
                  validatedCandidate,
                  latestCandidate.mainRevision,
                  latestCandidate.includedSubTasks,
                  latestCandidate.risks,
                  expectedCandidateId = latestCandidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, latestCandidate.id, validationChange),
                    listOfNotNull(validationChange),
                )
              }
          if (validatedCandidate.state == DeploymentCandidateState.FAILED) {
            val failed = validatedCandidate.copy(state = DeploymentCandidateState.FAILED)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(failed, DeploymentFailureStage.VALIDATION),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(failed),
                        eventLog = latest.deploymentEventLog(event, failed),
                    ),
                    listOfNotNull(validationChange),
                )
            )
          }
          if (validatedCandidate.state.isAfterValidation()) {
            val progressed = validatedCandidate
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(progressed, idempotent = true),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(progressed),
                        eventLog = latest.deploymentEventLog(event, progressed),
                    ),
                    listOfNotNull(validationChange),
                )
            )
          }

          val validationFailed =
              validatedCandidate.validations.any {
                it.required && it.status == ValidationStatus.FAILED
              }
          val validationPassed =
              validatedCandidate.validations.isNotEmpty() &&
                  requiredValidationsPassed(validatedCandidate.validations)
          if (validationFailed) {
            val failed = validatedCandidate.copy(state = DeploymentCandidateState.FAILED)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(failed, DeploymentFailureStage.VALIDATION),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(failed),
                        eventLog = latest.deploymentEventLog(event, failed),
                    ),
                    listOfNotNull(validationChange),
                )
            )
          }
          if (!validationPassed) {
            val validating = validatedCandidate.copy(state = DeploymentCandidateState.VALIDATING)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(validating),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(validating),
                        eventLog = latest.deploymentEventLog(event, validating),
                    ),
                    listOfNotNull(validationChange),
                )
            )
          }

          if (latestCandidate.risk == Risk.HIGH) {
            val awaiting =
                validatedCandidate.copy(state = DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL)
            return@transaction Operation.Commit(
                TransactionMutation(
                    DeploymentLifecycleResponse(awaiting),
                    latest.copy(
                        candidates = latest.candidates.replaceCandidate(awaiting),
                        eventLog = latest.deploymentEventLog(event, awaiting),
                    ),
                    listOfNotNull(validationChange),
                )
            )
          }

          val canary =
              when (
                  val result = deploymentPort.startCanary(StartCanaryRequest(latestCandidate.id))
              ) {
                is PortResult.Failure ->
                    return@transaction Operation.Abort(
                        result.withChange(validationChange),
                        listOfNotNull(validationChange),
                    )
                is PortResult.Success -> result.value
              }
          validateCandidateIdentity(
                  canary.candidate,
                  latestCandidate.mainRevision,
                  latestCandidate.includedSubTasks,
                  latestCandidate.risks,
                  expectedCandidateId = latestCandidate.id,
              )
              ?.let {
                return@transaction Operation.Abort(
                    failurePort(it.first, it.second, latestCandidate.id, canary.change),
                    listOfNotNull(validationChange, canary.change),
                )
              }
          if (canary.candidate.state != DeploymentCandidateState.CANARY) {
            return@transaction Operation.Abort(
                failurePort(
                    FailureCode.INVARIANT_VIOLATION,
                    "deployment provider did not start the candidate canary",
                    latestCandidate.id,
                    canary.change,
                ),
                listOfNotNull(validationChange, canary.change),
            )
          }
          Operation.Commit(
              TransactionMutation(
                  DeploymentLifecycleResponse(canary.candidate),
                  latest.copy(
                      candidates = latest.candidates.replaceCandidate(canary.candidate),
                      eventLog = latest.deploymentEventLog(event, canary.candidate),
                  ),
                  listOfNotNull(validationChange, canary.change),
              )
          )
        }
        .mapFailureStage()
  }

  /*
   * 명시적인 Canary 결과를 처리하는 별칭입니다.
   */
  fun handleCanaryEvent(
      request: DeploymentCanaryEventRequest
  ): WorkflowResult<DeploymentLifecycleResponse> = handle(request)

  /*
   * 외부 Canary 이벤트를 처리하는 별칭입니다.
   */
  fun handleCanaryEvent(event: ExternalEvent): WorkflowResult<DeploymentLifecycleResponse> =
      handle(event)

  private fun readSnapshot(expectedRevision: String?, target: String): SnapshotRead {
    val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))
    if (result is PortResult.Failure)
        return SnapshotRead.Failure(result.toWorkflowFailure(FailureCode.STORE_FAILURE))
    val snapshot = (result as PortResult.Success).value.snapshot
    if (expectedRevision != null && expectedRevision != snapshot.revision) {
      return SnapshotRead.Failure(
          failure(FailureCode.STALE_REVISION, "workflow store revision is stale", target)
      )
    }
    return SnapshotRead.Success(snapshot)
  }

  private fun mergedSubTasks(
      snapshot: WorkflowStoreSnapshot,
      mainRevision: MainRevision,
  ): MergedSubTasks {
    val production = latestProductionCandidate(snapshot)
    val eventMerges = snapshot.eventLog.events.filterIsInstance<MergeRecorded>()
    val integrationMerges =
        snapshot.integrations
            .filter { it.state == IntegrationState.MERGED && !it.mainRevision.isNullOrBlank() }
            .map {
              MergePoint(
                  subTaskId = it.subTaskId,
                  mainRevision = requireNotNull(it.mainRevision),
              )
            }
    val eventHasTarget = eventMerges.any { it.mainRevision == mainRevision }
    val eventHasProduction =
        production == null || eventMerges.any { it.mainRevision == production.mainRevision }
    val mergePoints =
        if (eventHasTarget && eventHasProduction) {
          eventMerges.map { MergePoint(it.targetId, it.mainRevision) }
        } else {
          integrationMerges
        }
    val targetFirstIndex = mergePoints.indexOfFirst { it.mainRevision == mainRevision }
    val targetLastIndex = mergePoints.indexOfLast { it.mainRevision == mainRevision }
    if (targetFirstIndex < 0) {
      return MergedSubTasks.Failure(
          failure(
              FailureCode.DEPLOYMENT_NOT_FOUND,
              "no merged subtasks were found for the main revision",
              mainRevision,
          )
      )
    }
    val productionIndex = production?.let { candidate ->
      mergePoints.indexOfLast { it.mainRevision == candidate.mainRevision }
    }
    if (production != null && productionIndex == -1) {
      return MergedSubTasks.Failure(
          failure(
              FailureCode.INVARIANT_VIOLATION,
              "the current production revision is missing from the merge history",
              production.mainRevision,
          )
      )
    }
    if (productionIndex != null && productionIndex >= 0 && targetFirstIndex < productionIndex) {
      return MergedSubTasks.Failure(
          failure(
              FailureCode.STATE_CONFLICT,
              "deployment target revision precedes the current production revision",
              mainRevision,
          )
      )
    }
    val firstCandidateIndex = productionIndex?.plus(1) ?: 0
    val targetPoints = mergePoints.subList(firstCandidateIndex, targetLastIndex + 1)
    val mergedIds = targetPoints.map(MergePoint::subTaskId).distinct()
    val subTasks = mergedIds.mapNotNull { id -> snapshot.subTasks.firstOrNull { it.id == id } }
    if (subTasks.size != mergedIds.size) {
      return MergedSubTasks.Failure(
          failure(
              FailureCode.INVARIANT_VIOLATION,
              "a merged integration references an unknown subtask",
              mainRevision,
          )
      )
    }
    return MergedSubTasks.Success(subTasks to subTasks.associate { it.id to it.risk })
  }

  private fun latestProductionCandidate(
      snapshot: WorkflowStoreSnapshot,
  ): DeploymentCandidate? {
    val productionEvents =
        snapshot.eventLog.events.filterIsInstance<DeploymentRecorded>().filter {
          it.state == DeploymentCandidateState.PRODUCTION
        }
    return productionEvents
        .asSequence()
        .mapNotNull { event -> snapshot.candidates.firstOrNull { it.id == event.targetId } }
        .lastOrNull { it.state == DeploymentCandidateState.PRODUCTION }
        ?: snapshot.candidates.lastOrNull { it.state == DeploymentCandidateState.PRODUCTION }
  }

  private fun validateCandidateIdentity(
      candidate: DeploymentCandidate,
      mainRevision: MainRevision,
      includedSubTasks: List<SubTaskId>,
      risks: Map<SubTaskId, Risk>,
      expectedCandidateId: CandidateId?,
  ): Pair<FailureCode, String>? =
      when {
        expectedCandidateId != null && candidate.id != expectedCandidateId ->
            FailureCode.INVARIANT_VIOLATION to "deployment provider returned a different candidate"
        candidate.mainRevision != mainRevision ->
            FailureCode.INVARIANT_VIOLATION to
                "deployment provider returned a different main revision"
        candidate.includedSubTasks != includedSubTasks ->
            FailureCode.INVARIANT_VIOLATION to "deployment provider changed included subtasks"
        candidate.risks != risks ->
            FailureCode.INVARIANT_VIOLATION to "deployment provider changed candidate risks"
        else -> null
      }

  private fun <T> transaction(
      request: StoreTransactionRequest,
      operation: (WorkflowStoreSnapshot) -> Operation<T>,
  ): WorkflowResult<T> {
    when (val begun = storePort.begin(request)) {
      is PortResult.Failure -> return begun.toWorkflowFailure(FailureCode.STORE_FAILURE)
      is PortResult.Success ->
          if (begun.value.state != StoreTransactionState.OPEN) {
            return failure(
                FailureCode.STORE_FAILURE,
                "workflow store transaction is not open",
                request.transactionId,
            )
          }
    }
    val snapshot =
        when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
          is PortResult.Failure -> {
            storePort.rollback(request)
            return result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          }
          is PortResult.Success -> result.value.snapshot
        }
    if (request.expectedRevision != null && request.expectedRevision != snapshot.revision) {
      storePort.rollback(request)
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.transactionId,
      )
    }
    return when (val outcome = operation(snapshot)) {
      is Operation.Abort -> {
        compensate(
            (outcome.changes + listOfNotNull(outcome.failure.change)).distinctBy(ChangeReceipt::id),
            outcome.failure.error.message,
        )
        storePort.rollback(request)
        outcome.failure.toWorkflowFailure(FailureCode.EXTERNAL_FAILURE)
      }
      is Operation.Commit -> {
        val write =
            storePort.write(
                StoreWriteRequest(
                    request.transactionId,
                    snapshot.revision,
                    outcome.mutation.snapshot,
                )
            )
        if (write is PortResult.Failure) {
          compensate(outcome.mutation.changes, write.error.message)
          storePort.rollback(request)
          return write.toWorkflowFailure(FailureCode.STORE_FAILURE)
        }
        val committed = storePort.commit(request)
        if (committed is PortResult.Failure)
            return committed.toWorkflowFailure(FailureCode.STORE_FAILURE)
        when (val failure = outcome.failure) {
          null -> WorkflowResult.Success(outcome.mutation.data)
          else -> WorkflowResult.Failure(failure)
        }
      }
    }
  }

  private fun compensate(changes: List<ChangeReceipt>, reason: String) {
    changes.asReversed().forEach { change ->
      if (change.compensation != null) {
        compensationGateway().compensate(CompensateRequest(change, reason))
      }
    }
  }

  private fun compensationGateway(): CompensationPort =
      compensationPort
          ?: object : CompensationPort {
            override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
                PortResult.Success(CompensateResponse(request.change))
          }

  private fun issueTransactionId(requestId: String): String =
      when (val result = idPort?.issue(IssueIdRequest(IdKind.TRANSACTION, requestId))) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value ?: "tx-$requestId"
        is PortResult.Failure,
        null,
        -> "tx-$requestId"
      }

  private fun nextActions(candidate: DeploymentCandidate): List<NextAction> =
      when (candidate.state) {
        DeploymentCandidateState.CANDIDATE,
        DeploymentCandidateState.VALIDATING,
        -> listOf(NextAction(io.springkit.workflow.domain.ActorKind.WORKFLOW, "validate_candidate"))
        DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL ->
            listOf(
                NextAction(
                    io.springkit.workflow.domain.ActorKind.HUMAN,
                    "approve_deployment",
                    "./tools/workflow/bin/workflow gate deploy ${candidate.id}",
                )
            )
        DeploymentCandidateState.CANARY ->
            listOf(NextAction(io.springkit.workflow.domain.ActorKind.WORKFLOW, "await_canary"))
        DeploymentCandidateState.PRODUCTION,
        DeploymentCandidateState.FAILED,
        DeploymentCandidateState.NOT_SELECTED,
        -> emptyList()
      }

  private fun failure(code: FailureCode, message: String, target: String): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code,
              message,
              blockedBy =
                  listOf(io.springkit.workflow.domain.BlockedBy(code.name, message, target)),
          )
      )

  private fun failurePort(
      code: FailureCode,
      message: String,
      target: String,
      change: ChangeReceipt? = null,
  ): PortResult.Failure = PortResult.Failure(PortError(code.name, message, target = target), change)

  private sealed interface SnapshotRead {
    data class Success(val snapshot: WorkflowStoreSnapshot) : SnapshotRead

    data class Failure(val failure: WorkflowResult.Failure) : SnapshotRead
  }

  private sealed interface MergedSubTasks {
    data class Success(val value: Pair<List<SubTask>, Map<SubTaskId, Risk>>) : MergedSubTasks

    data class Failure(val failure: WorkflowResult.Failure) : MergedSubTasks
  }

  private sealed interface Operation<T> {
    data class Commit<T>(
        val mutation: TransactionMutation<T>,
        val failure: FailureData? = null,
    ) : Operation<T>

    data class Abort<T>(
        val failure: PortResult.Failure,
        val changes: List<ChangeReceipt> = emptyList(),
    ) : Operation<T>
  }

  private data class MergePoint(
      val subTaskId: SubTaskId,
      val mainRevision: MainRevision,
  )
}

private fun <T> WorkflowResult<T>.mapFailureStage(): WorkflowResult<T> = this

private fun PortResult.Failure.withChange(change: ChangeReceipt?): PortResult.Failure =
    PortResult.Failure(error, this.change ?: change)

private fun WorkflowResult.Failure.toPortFailure(): PortResult.Failure =
    PortResult.Failure(
        PortError(
            code = data.code.name,
            message = data.message,
            target = data.blockedBy.firstOrNull()?.target,
        )
    )

private fun List<DeploymentCandidate>.replaceCandidate(
    value: DeploymentCandidate
): List<DeploymentCandidate> = map { current -> if (current.id == value.id) value else current }

private fun DeploymentCandidateState.isAfterValidation(): Boolean =
    this == DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL ||
        this == DeploymentCandidateState.CANARY ||
        this == DeploymentCandidateState.PRODUCTION

private fun DeploymentCandidateState.isActiveDeployment(): Boolean =
    this == DeploymentCandidateState.CANDIDATE ||
        this == DeploymentCandidateState.VALIDATING ||
        this == DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL ||
        this == DeploymentCandidateState.CANARY

private fun DeploymentCandidate.requiresValidationRefresh(): Boolean =
    validations.isEmpty() ||
        validations.any {
          it.required &&
              it.status != ValidationStatus.PASSED &&
              it.status != ValidationStatus.FAILED
        }

private fun WorkflowStoreSnapshot.deploymentEventLog(
    event: ExternalEvent,
    candidate: DeploymentCandidate,
): EventLog =
    eventLog.append(
        DeploymentRecorded(candidate.id, candidate.state, event.occurredAtEpochMillis),
        AuditEntry(
            id = event.id,
            actor = Actor("workflow", ActorKind.WORKFLOW),
            action = "deployment_changed",
            targetId = candidate.id,
            occurredAtEpochMillis = event.occurredAtEpochMillis,
        ),
    )
