package io.springkit.workflow.application

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.DeploymentRecorded
import io.springkit.workflow.domain.DomainEvent
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseId
import io.springkit.workflow.domain.ReleaseRecorded
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.nextReleaseAction

/*
 * Production 후보에서 Release를 만드는 요청입니다.
 */
data class CreateReleaseLifecycleRequest(
    val candidateId: CandidateId,
    val releaseId: ReleaseId? = null,
    val expectedStoreRevision: String? = null,
    val requestId: String = "release-create-$candidateId",
) {
  init {
    require(candidateId.isNotBlank()) { "release candidate id must not be blank" }
    require(releaseId == null || releaseId.isNotBlank()) { "release id must not be blank" }
    require(expectedStoreRevision == null || expectedStoreRevision.isNotBlank()) {
      "expected store revision must not be blank"
    }
    require(requestId.isNotBlank()) { "release request id must not be blank" }
  }
}

/*
 * Release 공급자와 Workflow Store에서 공개 대상을 조회하는 요청입니다.
 */
data class GetReleaseLifecycleRequest(
    val releaseId: ReleaseId? = null,
    val candidateId: CandidateId? = null,
) {
  init {
    require(releaseId != null || candidateId != null) {
      "release id or candidate id is required"
    }
    require(releaseId == null || releaseId.isNotBlank()) { "release id must not be blank" }
    require(candidateId == null || candidateId.isNotBlank()) { "candidate id must not be blank" }
    require(releaseId == null || candidateId == null) {
      "release id and candidate id cannot be used together"
    }
  }
}

/*
 * 내부 검수 결과를 Release에 반영하는 요청입니다.
 */
data class ValidateReleaseLifecycleRequest(
    val releaseId: ReleaseId,
    val requestId: String = "release-validate-$releaseId",
    val expectedStoreRevision: String? = null,
) {
  init {
    require(releaseId.isNotBlank()) { "release id must not be blank" }
    require(requestId.isNotBlank()) { "release validation request id must not be blank" }
    require(expectedStoreRevision == null || expectedStoreRevision.isNotBlank()) {
      "expected store revision must not be blank"
    }
  }
}

/*
 * 사람 Release Gate 이후 점진 공개 결과를 반영하는 요청입니다.
 */
data class ContinueRolloutLifecycleRequest(
    val releaseId: ReleaseId,
    val requestId: String = "release-rollout-$releaseId",
    val expectedStoreRevision: String? = null,
) {
  init {
    require(releaseId.isNotBlank()) { "release id must not be blank" }
    require(requestId.isNotBlank()) { "release rollout request id must not be blank" }
    require(expectedStoreRevision == null || expectedStoreRevision.isNotBlank()) {
      "expected store revision must not be blank"
    }
  }
}

/*
 * Production 후보와 연결된 Release입니다.
 */
data class ReleaseLifecycleResponse(
    val release: Release,
    val candidate: DeploymentCandidate,
    val next: List<NextAction> = nextReleaseAction(release)?.let(::listOf).orEmpty(),
)

/*
 * Release Event 처리 결과입니다. Release가 아직 만들어지지 않은 Production Event는 null을 반환합니다.
 */
data class ReleaseEventResponse(val response: ReleaseLifecycleResponse?)

/*
 * Release를 생성하고 내부 검수부터 rollout 완료까지 조정합니다.
 */
class ReleaseLifecycleUseCases(
    private val storePort: WorkflowStorePort,
    private val releasePort: ReleasePort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
    private val compensationPort: CompensationPort? = null,
) {
  /*
   * Production 후보에 Release를 만들고 내부 검수를 시작합니다.
   */
  fun create(request: CreateReleaseLifecycleRequest): WorkflowResult<ReleaseLifecycleResponse> {
    val snapshot = loadSnapshot() ?: return lastFailure
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.candidateId,
      )
    }
    val candidate =
        snapshot.candidates.firstOrNull { it.id == request.candidateId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                request.candidateId,
            )
    val existing = snapshot.releases.firstOrNull { it.candidateId == candidate.id }
    if (existing != null) {
      if (request.releaseId != null && request.releaseId != existing.id) {
        return failure(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "release request targets a different existing release",
            candidate.id,
        )
      }
      return responseFor(snapshot, existing)
    }
    if (candidate.state != DeploymentCandidateState.PRODUCTION) {
      return failure(
          FailureCode.RELEASE_GATE_BLOCKED,
          "release requires a production deployment candidate",
          candidate.id,
      )
    }
    if (request.releaseId != null && snapshot.releases.any { it.id == request.releaseId }) {
      return failure(
          FailureCode.IDEMPOTENCY_CONFLICT,
          "release request targets a different existing release",
          candidate.id,
      )
    }
    val releaseId = request.releaseId ?: issueReleaseId(request.requestId)
    if (releaseId == null) {
      return failure(FailureCode.EXTERNAL_FAILURE, "release id could not be issued", candidate.id)
    }
    val latestSnapshot =
        if (request.releaseId == null) {
          loadSnapshot() ?: return lastFailure
        } else {
          snapshot
        }
    val latestCandidate =
        if (request.releaseId == null) {
          latestSnapshot.candidates.firstOrNull { it.id == request.candidateId }
              ?: return failure(
                  FailureCode.DEPLOYMENT_NOT_FOUND,
                  "deployment candidate was not found",
                  request.candidateId,
              )
        } else {
          candidate
        }
    val latestExisting =
        latestSnapshot.releases.firstOrNull { it.candidateId == latestCandidate.id }
    if (latestExisting != null) {
      if (request.releaseId != null && request.releaseId != latestExisting.id) {
        return failure(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "release request targets a different existing release",
            latestCandidate.id,
        )
      }
      return responseFor(latestSnapshot, latestExisting)
    }
    if (request.releaseId != null && latestSnapshot.releases.any { it.id == request.releaseId }) {
      return failure(
          FailureCode.IDEMPOTENCY_CONFLICT,
          "release request targets a different existing release",
          latestCandidate.id,
      )
    }
    if (latestCandidate.state != DeploymentCandidateState.PRODUCTION) {
      return failure(
          FailureCode.RELEASE_GATE_BLOCKED,
          "release requires a production deployment candidate",
          latestCandidate.id,
      )
    }
    val transaction =
        transaction(
            latestSnapshot.revision,
            "release-create:${request.requestId}",
        )
    return executeReleaseTransaction(transaction) { current, changes ->
      createAndValidate(current, request, latestCandidate, releaseId, changes)
    }
  }

  /*
   * Release를 Store와 공급자에서 조회합니다.
   */
  fun get(request: GetReleaseLifecycleRequest): WorkflowResult<ReleaseLifecycleResponse> {
    val snapshot = loadSnapshot() ?: return lastFailure
    val stored =
        if (request.releaseId != null) {
          snapshot.releases.firstOrNull { it.id == request.releaseId }
        } else {
          snapshot.releases.firstOrNull { it.candidateId == request.candidateId }
        }
            ?: return failure(
                FailureCode.RELEASE_NOT_FOUND,
                "release was not found",
                request.releaseId ?: request.candidateId!!,
            )
    val provider =
        when (val result = releasePort.get(GetReleaseRequest(releaseId = stored.id))) {
          is PortResult.Failure -> return result.toWorkflowFailure(FailureCode.EXTERNAL_FAILURE)
          is PortResult.Success ->
              result.value.releases.singleOrNull { it.id == stored.id }
                  ?: return failure(
                      FailureCode.RELEASE_NOT_FOUND,
                      "release provider did not return the requested release",
                      stored.id,
                  )
        }
    if (provider.candidateId != stored.candidateId) {
      return failure(
          FailureCode.INVARIANT_VIOLATION,
          "release provider returned a different release target",
          stored.id,
      )
    }
    return responseFor(snapshot, provider)
  }

  /*
   * 내부 검수 결과를 확인하고 production 공개 승인 대기 상태로 전이합니다.
   */
  fun validateInternal(
      request: ValidateReleaseLifecycleRequest
  ): WorkflowResult<ReleaseLifecycleResponse> {
    val snapshot = loadSnapshot() ?: return lastFailure
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.releaseId,
      )
    }
    val stored =
        snapshot.releases.firstOrNull { it.id == request.releaseId }
            ?: return failure(
                FailureCode.RELEASE_NOT_FOUND,
                "release was not found",
                request.releaseId,
            )
    if (
        stored.state == ReleaseState.AWAITING_RELEASE_APPROVAL ||
            stored.state == ReleaseState.ROLLOUT ||
            stored.state == ReleaseState.CLEANUP_REQUIRED
    ) {
      return responseFor(snapshot, stored)
    }
    val candidate =
        snapshot.candidates.firstOrNull { it.id == stored.candidateId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                stored.candidateId,
            )
    val transaction = transaction(snapshot.revision, "release-validate:${request.requestId}")
    return executeReleaseTransaction(transaction) { current, changes ->
      validateIn(current, request, stored, candidate, changes)
    }
  }

  /*
   * Release Gate 이후 공급자의 점진 공개 결과를 반영합니다.
   */
  fun continueRollout(
      request: ContinueRolloutLifecycleRequest
  ): WorkflowResult<ReleaseLifecycleResponse> {
    val snapshot = loadSnapshot() ?: return lastFailure
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != snapshot.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.releaseId,
      )
    }
    val stored =
        snapshot.releases.firstOrNull { it.id == request.releaseId }
            ?: return failure(
                FailureCode.RELEASE_NOT_FOUND,
                "release was not found",
                request.releaseId,
            )
    if (stored.state == ReleaseState.CLEANUP_REQUIRED) {
      return responseFor(snapshot, stored)
    }
    if (stored.state == ReleaseState.RELEASED) {
      val cleaned = stored.copy(state = ReleaseState.CLEANUP_REQUIRED)
      val transaction = transaction(snapshot.revision, "release-cleanup:${request.requestId}")
      return executeReleaseTransaction(transaction) { current, _ ->
        PortResult.Success(
            TransactionMutation(
                data = responseData(current, cleaned),
                snapshot =
                    current.copy(
                        releases = current.releases.replaceById(cleaned) { value -> value.id },
                        eventLog =
                            current.eventLog.append(
                                ReleaseRecorded(cleaned.id, cleaned.state, now(request.requestId)),
                            ),
                    ),
            ),
        )
      }
    }
    if (stored.state != ReleaseState.ROLLOUT) {
      return failure(FailureCode.RELEASE_GATE_BLOCKED, "release is not in rollout", stored.id)
    }
    val candidate =
        snapshot.candidates.firstOrNull { it.id == stored.candidateId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                stored.candidateId,
            )
    val transaction = transaction(snapshot.revision, "release-rollout:${request.requestId}")
    return executeReleaseTransaction(transaction) { current, changes ->
      val continued =
          when (val result = releasePort.continueRollout(ContinueReleaseRequest(stored.id))) {
            is PortResult.Failure -> {
              changes += result.change
              return@executeReleaseTransaction result
            }
            is PortResult.Success -> {
              changes += result.change
              result.value
            }
          }
      if (
          continued.release.id != stored.id || continued.release.candidateId != stored.candidateId
      ) {
        return@executeReleaseTransaction failurePort(
            FailureCode.INVARIANT_VIOLATION,
            "release provider returned a different rollout target",
            stored.id,
            continued.change,
        )
      }
      val updated = normalizeRolloutResult(continued.release)
      val event = ReleaseRecorded(updated.id, updated.state, now(request.requestId))
      PortResult.Success(
          TransactionMutation(
              data = responseData(current, updated, candidate),
              snapshot =
                  current.copy(
                      releases = current.releases.replaceById(updated) { value -> value.id },
                      eventLog = current.eventLog.append(event),
                  ),
              changes = listOf(continued.change),
          )
      )
    }
  }

  /*
   * Production 배포 Event를 반영하고 필요한 Release를 멱등적으로 생성합니다.
   */
  fun handleDeployment(event: DeploymentRecorded): WorkflowResult<ReleaseLifecycleResponse?> {
    return when (val result = handleDeploymentAll(event)) {
      is WorkflowResult.Failure -> result
      is WorkflowResult.Success -> WorkflowResult.Success(result.data.firstOrNull())
    }
  }

  /*
   * Production 배포 Event를 반영해 candidate Release를 멱등적으로 생성합니다.
   */
  fun handleDeploymentAll(
      event: DeploymentRecorded
  ): WorkflowResult<List<ReleaseLifecycleResponse>> {
    val snapshot = loadSnapshot() ?: return lastFailure
    val candidate =
        snapshot.candidates.firstOrNull { it.id == event.targetId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                event.targetId,
            )
    if (event.state != DeploymentCandidateState.PRODUCTION) {
      return WorkflowResult.Success(emptyList())
    }
    return when (
        val result =
            create(
                CreateReleaseLifecycleRequest(
                    candidateId = candidate.id,
                    requestId = "event-${event.targetId}-${event.occurredAtEpochMillis}",
                )
            )
    ) {
      is WorkflowResult.Failure -> result
      is WorkflowResult.Success -> WorkflowResult.Success(listOf(result.data))
    }
  }

  /*
   * Release Event를 반영해 내부 검수, rollout과 cleanup 다음 행동을 계산합니다.
   */
  fun handleRelease(event: ReleaseRecorded): WorkflowResult<ReleaseLifecycleResponse> {
    val snapshot = loadSnapshot() ?: return lastFailure
    val stored =
        snapshot.releases.firstOrNull { it.id == event.targetId }
            ?: return failure(
                FailureCode.RELEASE_NOT_FOUND,
                "release was not found",
                event.targetId,
            )
    val candidate =
        snapshot.candidates.firstOrNull { it.id == stored.candidateId }
            ?: return failure(
                FailureCode.DEPLOYMENT_NOT_FOUND,
                "deployment candidate was not found",
                stored.candidateId,
            )
    val updated = normalizeEventResult(stored, event.state)
    if (updated == stored) return responseFor(snapshot, stored, candidate)
    val transaction =
        transaction(
            snapshot.revision,
            "release-event:${event.targetId}-${event.occurredAtEpochMillis}",
        )
    return executeReleaseTransaction(transaction) { current, _ ->
      PortResult.Success(
          TransactionMutation(
              data = responseData(current, updated, candidate),
              snapshot =
                  current.copy(
                      releases = current.releases.replaceById(updated) { value -> value.id },
                      eventLog = current.eventLog.append(event),
                  ),
          )
      )
    }
  }

  /*
   * 외부 Event의 종류에 따라 Release lifecycle을 처리합니다.
   */
  fun handle(event: DomainEvent): WorkflowResult<*> =
      when (event) {
        is DeploymentRecorded -> handleDeployment(event)
        is ReleaseRecorded -> handleRelease(event)
        else ->
            failure(
                FailureCode.INVALID_ARGUMENT,
                "unsupported release lifecycle event",
                event.targetId,
            )
      }

  private fun createAndValidate(
      snapshot: WorkflowStoreSnapshot,
      request: CreateReleaseLifecycleRequest,
      candidate: DeploymentCandidate,
      releaseId: ReleaseId,
      changes: MutableList<ChangeReceipt?>,
  ): PortResult<TransactionMutation<ReleaseLifecycleResponse>> {
    val created =
        when (val result = releasePort.create(CreateReleaseRequest(releaseId, candidate.id))) {
          is PortResult.Failure -> {
            changes += result.change
            return result
          }
          is PortResult.Success -> {
            changes += result.change
            result.value
          }
        }
    if (created.release.id != releaseId || created.release.candidateId != candidate.id) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "release provider returned an invalid release",
          releaseId,
      )
    }
    val validating =
        when (val result = releasePort.validateInternal(ValidateReleaseRequest(releaseId))) {
          is PortResult.Failure -> {
            changes += result.change
            return PortResult.Failure(result.error, result.change ?: created.change)
          }
          is PortResult.Success -> {
            changes += result.change
            result.value
          }
        }
    if (validating.release.id != releaseId || validating.release.candidateId != candidate.id) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "internal validation returned an invalid release",
          releaseId,
      )
    }
    val updated = normalizeValidationResult(validating.release, candidate)
    val event = ReleaseRecorded(updated.id, updated.state, now(request.requestId))
    return PortResult.Success(
        TransactionMutation(
            data = responseData(snapshot, updated, candidate),
            snapshot =
                snapshot.copy(
                    releases = snapshot.releases.replaceOrAdd(updated) { value -> value.id },
                    eventLog = snapshot.eventLog.append(event),
                ),
            changes = listOf(created.change, validating.change),
        )
    )
  }

  private fun validateIn(
      snapshot: WorkflowStoreSnapshot,
      request: ValidateReleaseLifecycleRequest,
      stored: Release,
      candidate: DeploymentCandidate,
      changes: MutableList<ChangeReceipt?>,
  ): PortResult<TransactionMutation<ReleaseLifecycleResponse>> {
    val validated =
        when (val result = releasePort.validateInternal(ValidateReleaseRequest(stored.id))) {
          is PortResult.Failure -> {
            changes += result.change
            return result
          }
          is PortResult.Success -> {
            changes += result.change
            result.value
          }
        }
    if (validated.release.id != stored.id || validated.release.candidateId != candidate.id) {
      return failurePort(
          FailureCode.INVARIANT_VIOLATION,
          "internal validation returned an invalid release",
          stored.id,
      )
    }
    val updated = normalizeValidationResult(validated.release, candidate)
    val event = ReleaseRecorded(updated.id, updated.state, now(request.requestId))
    return PortResult.Success(
        TransactionMutation(
            data = responseData(snapshot, updated, candidate),
            snapshot =
                snapshot.copy(
                    releases = snapshot.releases.replaceById(updated) { value -> value.id },
                    eventLog = snapshot.eventLog.append(event),
                ),
            changes = listOf(validated.change),
        )
    )
  }

  private fun normalizeValidationResult(
      release: Release,
      candidate: DeploymentCandidate,
  ): Release {
    val productionReady = candidate.state == DeploymentCandidateState.PRODUCTION
    val passed = release.internalValidationPassed
    return release.copy(
        state =
            if (productionReady && passed) ReleaseState.AWAITING_RELEASE_APPROVAL
            else ReleaseState.INTERNAL_VALIDATION,
        productionReady = productionReady,
    )
  }

  private fun normalizeRolloutResult(release: Release): Release =
      if (release.state == ReleaseState.RELEASED) {
        release.copy(state = ReleaseState.CLEANUP_REQUIRED)
      } else {
        release
      }

  private fun normalizeEventResult(release: Release, state: ReleaseState): Release =
      if (releaseStateRank(state) < releaseStateRank(release.state)) {
        release
      } else {
        when (state) {
          ReleaseState.RELEASED -> release.copy(state = ReleaseState.CLEANUP_REQUIRED)
          ReleaseState.AWAITING_RELEASE_APPROVAL ->
              release.copy(state = state, internalValidationPassed = true, productionReady = true)
          else -> release.copy(state = state)
        }
      }

  private fun releaseStateRank(state: ReleaseState): Int =
      when (state) {
        ReleaseState.NOT_APPLICABLE -> -1
        ReleaseState.SAFE_DEFAULT -> 0
        ReleaseState.INTERNAL_VALIDATION -> 1
        ReleaseState.AWAITING_RELEASE_APPROVAL -> 2
        ReleaseState.ROLLOUT -> 3
        ReleaseState.RELEASED,
        ReleaseState.CLEANUP_REQUIRED,
        -> 4
      }

  private fun responseFor(
      snapshot: WorkflowStoreSnapshot,
      release: Release,
      candidate: DeploymentCandidate? =
          snapshot.candidates.firstOrNull { it.id == release.candidateId },
  ): WorkflowResult<ReleaseLifecycleResponse> {
    if (candidate == null)
        return failure(
            FailureCode.DEPLOYMENT_NOT_FOUND,
            "deployment candidate was not found",
            release.candidateId,
        )
    return WorkflowResult.Success(responseData(snapshot, release, candidate))
  }

  private fun responseData(
      snapshot: WorkflowStoreSnapshot,
      release: Release,
      candidate: DeploymentCandidate? =
          snapshot.candidates.firstOrNull { it.id == release.candidateId },
  ): ReleaseLifecycleResponse =
      ReleaseLifecycleResponse(
          release = release,
          candidate = requireNotNull(candidate),
      )

  private var lastFailure: WorkflowResult.Failure =
      failure(FailureCode.STORE_FAILURE, "workflow store lookup failed", "store")

  private fun loadSnapshot(): WorkflowStoreSnapshot? =
      when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
        is PortResult.Failure -> {
          lastFailure = result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          null
        }
        is PortResult.Success -> result.value.snapshot
      }

  private fun issueReleaseId(requestId: String): ReleaseId? =
      when (val result = idPort?.issue(IssueIdRequest(IdKind.RELEASE, requestId))) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value
        is PortResult.Failure,
        null -> null
      }

  private fun transaction(revision: String, operation: String): StoreTransactionRequest =
      StoreTransactionRequest(
          transactionId = issueTransactionId(operation),
          expectedRevision = revision,
          idempotencyKey = operation,
      )

  private fun issueTransactionId(requestId: String): String =
      when (val result = idPort?.issue(IssueIdRequest(IdKind.TRANSACTION, requestId))) {
        is PortResult.Success -> result.value.ids.firstOrNull()?.value ?: "tx-$requestId"
        is PortResult.Failure,
        null -> "tx-$requestId"
      }

  private fun <T> executeReleaseTransaction(
      request: StoreTransactionRequest,
      operation:
          (WorkflowStoreSnapshot, MutableList<ChangeReceipt?>) -> PortResult<
                  TransactionMutation<T>
              >,
  ): WorkflowResult<T> {
    val begun = storePort.begin(request)
    if (begun is PortResult.Failure) {
      return begun.toWorkflowFailure(FailureCode.STORE_FAILURE)
    }
    val snapshotResult = storePort.snapshot(StoreSnapshotRequest())
    if (snapshotResult is PortResult.Failure) {
      storePort.rollback(request)
      return snapshotResult.toWorkflowFailure(FailureCode.STORE_FAILURE)
    }
    val snapshot = (snapshotResult as PortResult.Success).value.snapshot
    val changes = mutableListOf<ChangeReceipt?>()
    return when (val result = operation(snapshot, changes)) {
      is PortResult.Failure -> {
        result.change?.let { changes += it }
        compensate(changes, result.error.message)
        storePort.rollback(request)
        result.toWorkflowFailure(FailureCode.EXTERNAL_FAILURE)
      }
      is PortResult.Success -> {
        val mutation = result.value
        val allChanges =
            (changes.filterNotNull() + mutation.changes).distinctBy { change -> change.id }
        when (
            val write =
                storePort.write(
                    StoreWriteRequest(
                        transactionId = request.transactionId,
                        expectedRevision = mutation.snapshot.revision,
                        snapshot = mutation.snapshot,
                    )
                )
        ) {
          is PortResult.Failure -> {
            compensate(allChanges, write.error.message)
            storePort.rollback(request)
            write.toWorkflowFailure(FailureCode.STORE_FAILURE)
          }
          is PortResult.Success -> {
            when (val committed = storePort.commit(request)) {
              is PortResult.Failure -> committed.toWorkflowFailure(FailureCode.STORE_FAILURE)
              is PortResult.Success -> WorkflowResult.Success(mutation.data)
            }
          }
        }
      }
    }
  }

  private fun compensate(changes: List<ChangeReceipt?>, reason: String) {
    changes
        .filterNotNull()
        .asReversed()
        .distinctBy { change -> change.id }
        .forEach { change ->
          if (change.compensation != null) {
            compensationGateway().compensate(CompensateRequest(change, reason))
          }
        }
  }

  private fun now(requestId: String): Long =
      when (val result = clockPort?.now(NowRequest(requestId))) {
        is PortResult.Success -> result.value.epochMillis
        is PortResult.Failure,
        null -> 0L
      }

  private fun compensationGateway(): CompensationPort =
      compensationPort
          ?: object : CompensationPort {
            override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
                PortResult.Success(CompensateResponse(request.change))
          }

  private fun failure(
      code: FailureCode,
      message: String,
      target: String,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(code, message, blockedBy = listOf(BlockedBy(code.name, message, target))),
      )

  private fun failurePort(
      code: FailureCode,
      message: String,
      target: String,
      change: ChangeReceipt? = null,
  ): PortResult.Failure = PortResult.Failure(PortError(code.name, message, target = target), change)
}

private fun <T> List<T>.replaceById(value: T, id: (T) -> String): List<T> = map { current ->
  if (id(current) == id(value)) value else current
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) replaceById(value, id) else this + value
