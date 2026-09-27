package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.GateRecorded
import io.springkit.workflow.domain.GateType
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseId
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkflowRules

/*
 * 고위험 배포와 외부 공개를 시작하기 위한 사람 Gate 요청입니다.
 */
data class DeployGateRequest(
    val candidateId: CandidateId,
    val requestId: String = "deploy-$candidateId",
) {
  init {
    require(candidateId.isNotBlank()) { "deployment candidate id must not be blank" }
    require(requestId.isNotBlank()) { "deployment gate request id must not be blank" }
  }
}

/*
 * Release를 시작하기 위한 사람 Gate 요청입니다.
 */
data class ReleaseGateRequest(
    val releaseId: ReleaseId,
    val requestId: String = "release-$releaseId",
) {
  init {
    require(releaseId.isNotBlank()) { "release id must not be blank" }
    require(requestId.isNotBlank()) { "release gate request id must not be blank" }
  }
}

/*
 * 배포와 공개 Gate 유스케이스를 하나의 애플리케이션 경계로 제공합니다.
 */
class DeliveryGateUseCases(
    private val storePort: WorkflowStorePort,
    private val deploymentPort: DeploymentPort,
    private val releasePort: ReleasePort,
    private val identityPort: IdentityPort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort = NoOpCompensationPort,
) {
  /*
   * 사람의 배포 승인을 확인하고 고위험 후보의 Production Canary를 시작합니다.
   */
  fun deploy(request: DeployGateRequest): WorkflowResult<StartCanaryResponse> =
      executeGate(
          requestId = request.requestId,
          targetId = request.candidateId,
          capability = Capability.DEPLOY,
          gate = GateType.DEPLOY,
      ) { actor, snapshot ->
        val stored = snapshot.candidates.firstOrNull { it.id == request.candidateId }
        if (stored == null) {
          return@executeGate failurePort(
              FailureCode.DEPLOYMENT_NOT_FOUND,
              "deployment candidate was not found",
              request.candidateId,
          )
        }

        val current =
            when (
                val result = deploymentPort.getCandidate(GetCandidateRequest(request.candidateId))
            ) {
              is PortResult.Failure -> return@executeGate result
              is PortResult.Success -> result.value.candidate
            }
        validateDeploymentProviderResult(current, stored, stored.state)?.let {
          return@executeGate failurePort(FailureCode.INVARIANT_VIOLATION, it, request.candidateId)
        }

        gateFailure(GateType.DEPLOY, actor, stored, current, request.candidateId)?.let {
          return@executeGate it
        }
        val started =
            when (val result = deploymentPort.startCanary(StartCanaryRequest(current.id, actor))) {
              is PortResult.Failure -> return@executeGate result
              is PortResult.Success -> result.value
            }
        validateDeploymentProviderResult(
                started.candidate,
                current,
                DeploymentCandidateState.CANARY,
            )
            ?.let {
              return@executeGate failurePort(
                  FailureCode.INVARIANT_VIOLATION,
                  it,
                  request.candidateId,
                  started.change,
              )
            }

        val occurredAt = now(request.requestId)
        val event = GateRecorded(current.id, GateType.DEPLOY, actor, occurredAt)
        val audit =
            AuditEntry(
                id = auditId(request.requestId, GateType.DEPLOY, current.id),
                actor = actor,
                action = "deploy",
                targetId = current.id,
                mainRevision = current.mainRevision,
                occurredAtEpochMillis = occurredAt,
            )
        PortResult.Success(
            TransactionMutation(
                data = started,
                snapshot =
                    snapshot.copy(
                        candidates = snapshot.candidates.replaceById(started.candidate) { it.id },
                        eventLog = snapshot.eventLog.append(event, audit),
                    ),
                changes = listOf(started.change),
            )
        )
      }

  /*
   * 사람의 공개 승인을 확인하고 내부 검수가 끝난 Release를 시작합니다.
   */
  fun release(request: ReleaseGateRequest): WorkflowResult<StartReleaseResponse> =
      executeGate(
          requestId = request.requestId,
          targetId = request.releaseId,
          capability = Capability.RELEASE,
          gate = GateType.RELEASE,
      ) { actor, snapshot ->
        val stored = snapshot.releases.firstOrNull { it.id == request.releaseId }
        if (stored == null) {
          return@executeGate failurePort(
              FailureCode.RELEASE_NOT_FOUND,
              "release was not found",
              request.releaseId,
          )
        }

        val current =
            when (val result = releasePort.get(GetReleaseRequest(releaseId = request.releaseId))) {
              is PortResult.Failure -> return@executeGate result
              is PortResult.Success ->
                  result.value.releases.singleOrNull { it.id == request.releaseId }
                      ?: return@executeGate failurePort(
                          FailureCode.RELEASE_NOT_FOUND,
                          "release provider did not return the requested release",
                          request.releaseId,
                      )
            }
        validateReleaseProviderResult(current, stored, stored.state)?.let {
          return@executeGate failurePort(FailureCode.INVARIANT_VIOLATION, it, request.releaseId)
        }

        gateFailure(GateType.RELEASE, actor, stored, current, request.releaseId)?.let {
          return@executeGate it
        }

        val started =
            when (val result = releasePort.start(StartReleaseRequest(current.id, actor))) {
              is PortResult.Failure -> return@executeGate result
              is PortResult.Success -> result.value
            }
        validateReleaseProviderResult(started.release, current, ReleaseState.ROLLOUT)?.let {
          return@executeGate failurePort(
              FailureCode.INVARIANT_VIOLATION,
              it,
              request.releaseId,
              started.change,
          )
        }

        val occurredAt = now(request.requestId)
        val event = GateRecorded(current.id, GateType.RELEASE, actor, occurredAt)
        val audit =
            AuditEntry(
                id = auditId(request.requestId, GateType.RELEASE, current.id),
                actor = actor,
                action = "release",
                targetId = current.id,
                mainRevision =
                    snapshot.candidates.firstOrNull { it.id == current.candidateId }?.mainRevision,
                occurredAtEpochMillis = occurredAt,
            )
        PortResult.Success(
            TransactionMutation(
                data = started,
                snapshot =
                    snapshot.copy(
                        releases = snapshot.releases.replaceById(started.release) { it.id },
                        eventLog = snapshot.eventLog.append(event, audit),
                    ),
                changes = listOf(started.change),
            )
        )
      }

  /*
   * 배포 요청을 애플리케이션 명령 형태로 실행합니다.
   */
  fun execute(request: DeployGateRequest): WorkflowResult<StartCanaryResponse> = deploy(request)

  /*
   * 공개 요청을 애플리케이션 명령 형태로 실행합니다.
   */
  fun execute(request: ReleaseGateRequest): WorkflowResult<StartReleaseResponse> = release(request)

  /*
   * 배포 요청을 인바운드 어댑터의 공통 진입점으로 처리합니다.
   */
  fun handle(request: DeployGateRequest): WorkflowResult<StartCanaryResponse> = deploy(request)

  /*
   * 공개 요청을 인바운드 어댑터의 공통 진입점으로 처리합니다.
   */
  fun handle(request: ReleaseGateRequest): WorkflowResult<StartReleaseResponse> = release(request)

  private fun <T> executeGate(
      requestId: String,
      targetId: String,
      capability: Capability,
      gate: GateType,
      operation: (Actor, WorkflowStoreSnapshot) -> PortResult<TransactionMutation<T>>,
  ): WorkflowResult<T> {
    val actor =
        when (val result = identityPort.currentActor(CurrentActorRequest(requestId))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.HUMAN_REQUIRED)
          is PortResult.Success -> result.value.actor
        }
    val authorization =
        when (val result = identityPort.authorize(AuthorizeRequest(actor, capability, targetId))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.HUMAN_REQUIRED)
          is PortResult.Success -> result.value
        }
    if (!authorization.allowed) {
      return failure(
          FailureCode.HUMAN_REQUIRED,
          authorization.reason ?: "the actor is not authorized for this human gate",
          targetId,
      )
    }
    if (!actor.isHuman) {
      return failure(
          FailureCode.HUMAN_REQUIRED,
          "${gate.name.lowercase()} gate requires a human actor",
          targetId,
      )
    }

    val transaction = WorkflowTransaction(storePort, compensationPort)
    return transaction.execute(
        StoreTransactionRequest(
            transactionId = "gate-${gate.name.lowercase()}-$targetId-$requestId",
            idempotencyKey = requestId,
        ),
    ) { snapshot ->
      operation(actor, snapshot)
    }
  }

  private fun gateFailure(
      gate: GateType,
      actor: Actor,
      stored: DeploymentCandidate,
      current: DeploymentCandidate,
      targetId: String,
  ): PortResult.Failure? {
    val storedFailure = WorkflowRules.humanGateCondition(gate, actor, candidate = stored)
    val currentFailure = WorkflowRules.humanGateCondition(gate, actor, candidate = current)
    return (storedFailure ?: currentFailure)?.let {
      failurePort(
          if (it.code == FailureCode.HUMAN_REQUIRED) it.code
          else FailureCode.DEPLOYMENT_GATE_BLOCKED,
          it.message,
          targetId,
      )
    }
  }

  private fun validateDeploymentProviderResult(
      actual: DeploymentCandidate,
      expected: DeploymentCandidate,
      expectedState: DeploymentCandidateState,
  ): String? =
      when {
        actual.id != expected.id -> "deployment provider returned a different candidate"
        actual.mainRevision != expected.mainRevision ->
            "deployment provider returned a different main revision"
        actual.includedSubTasks != expected.includedSubTasks ->
            "deployment provider changed included subtasks"
        actual.risks != expected.risks -> "deployment provider changed candidate risks"
        actual.validations != expected.validations ->
            "deployment provider changed candidate validations"
        actual.state != expectedState ->
            "deployment provider returned an unexpected candidate state"
        else -> null
      }

  private fun validateReleaseProviderResult(
      actual: Release,
      expected: Release,
      expectedState: ReleaseState,
  ): String? =
      when {
        actual.id != expected.id -> "release provider returned a different release"
        actual.candidateId != expected.candidateId ->
            "release provider returned a different candidate"
        actual.cleanupSubTaskId != expected.cleanupSubTaskId ->
            "release provider changed the cleanup subtask"
        actual.state != expectedState -> "release provider returned an unexpected release state"
        actual.productionReady != expected.productionReady ->
            "release provider changed production readiness"
        actual.internalValidationPassed != expected.internalValidationPassed ->
            "release provider changed internal validation readiness"
        else -> null
      }

  private fun gateFailure(
      gate: GateType,
      actor: Actor,
      stored: Release,
      current: Release,
      targetId: String,
  ): PortResult.Failure? {
    val storedFailure = WorkflowRules.humanGateCondition(gate, actor, release = stored)
    val currentFailure = WorkflowRules.humanGateCondition(gate, actor, release = current)
    return (storedFailure ?: currentFailure)?.let {
      failurePort(
          if (it.code == FailureCode.HUMAN_REQUIRED) it.code else FailureCode.RELEASE_GATE_BLOCKED,
          it.message,
          targetId,
      )
    }
  }

  private fun failure(
      code: FailureCode,
      message: String,
      target: String,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code = code,
              message = message,
              blockedBy = listOf(BlockedBy(code.name, message, target)),
          ),
      )

  private fun failurePort(
      code: FailureCode,
      message: String,
      target: String,
      change: ChangeReceipt? = null,
  ): PortResult.Failure = PortResult.Failure(PortError(code.name, message, target = target), change)

  private fun auditId(requestId: String, gate: GateType, targetId: String): String =
      when (
          val result = idPort?.issue(IssueIdRequest(IdKind.AUDIT, "$gate:$targetId:$requestId"))
      ) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value ?: "audit-$gate-$targetId"
        is PortResult.Failure,
        null,
        -> "audit-${gate.name.lowercase()}-$targetId-$requestId"
      }

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null,
        -> 0L
      }

  private companion object {
    val NoOpCompensationPort: CompensationPort =
        object : CompensationPort {
          override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
              PortResult.Success(CompensateResponse(request.change))
        }
  }
}

/*
 * 배포 Gate 요청을 기존 명명 규칙으로 사용할 수 있게 하는 별칭입니다.
 */
typealias DeploymentGateRequest = DeployGateRequest

/*
 * 공개 Gate 요청을 기존 명명 규칙으로 사용할 수 있게 하는 별칭입니다.
 */
typealias ExternalReleaseGateRequest = ReleaseGateRequest

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> =
    filterNot { id(it) == id(value) } + value
