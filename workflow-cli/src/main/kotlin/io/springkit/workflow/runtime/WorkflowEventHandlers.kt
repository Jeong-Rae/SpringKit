package io.springkit.workflow.runtime

import io.springkit.workflow.application.ContinueRolloutLifecycleRequest
import io.springkit.workflow.application.DeploymentLifecycleRequest
import io.springkit.workflow.application.DeploymentLifecycleResponse
import io.springkit.workflow.application.DeploymentLifecycleUseCase
import io.springkit.workflow.application.ExternalEvent
import io.springkit.workflow.application.ExternalEventKind
import io.springkit.workflow.application.MergeQueueLifecycleRequest
import io.springkit.workflow.application.PostMergeCleanupRequest
import io.springkit.workflow.application.PostMergeCleanupResponse
import io.springkit.workflow.application.ReleaseLifecycleResponse
import io.springkit.workflow.application.ReleaseLifecycleUseCases
import io.springkit.workflow.application.WorkflowEventHandler
import io.springkit.workflow.application.WorkflowEventPort
import io.springkit.workflow.application.WorkflowEventUseCases
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.DeploymentRecorded
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.ReleaseRecorded
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.WorkflowResult

/** 배포 Event의 후보, Release와 후속 후보를 함께 반환하는 런타임 결과입니다. */
data class DeploymentEventResponse(
    val deployment: DeploymentLifecycleResponse,
    val releases: List<ReleaseLifecycleResponse> = emptyList(),
    val nextCandidate: DeploymentLifecycleResponse? = null,
)

/** 외부 Event와 해당 lifecycle을 연결하는 handler를 조립합니다. */
internal fun createWorkflowEventUseCases(
    eventPort: WorkflowEventPort,
    providerConfigured: Boolean,
    mergeQueueLifecycle: io.springkit.workflow.application.MergeQueueLifecycleUseCase,
    postMergeCleanup: io.springkit.workflow.application.PostMergeCleanupUseCase,
    deploymentLifecycle: DeploymentLifecycleUseCase,
    releaseLifecycle: ReleaseLifecycleUseCases,
    store: WorkflowStorePort,
): WorkflowEventUseCases {
  val handlers =
      mutableMapOf(
          ExternalEventKind.MERGE_QUEUE_CHANGED to
              WorkflowEventHandler { event ->
                val mergeRequest =
                    MergeQueueLifecycleRequest(
                        subTaskId = event.targetId,
                        mergeQueueEntryId =
                            event.attributes.optionalAttribute("merge_queue_entry_id"),
                        changeRevisionId = event.attributes.optionalAttribute("change_revision_id"),
                        requestId = event.id,
                    )
                when (val merged = mergeQueueLifecycle.execute(mergeRequest)) {
                  is WorkflowResult.Failure -> merged
                  is WorkflowResult.Success -> {
                    val mainRevision = merged.data.integration.mainRevision
                    if (mainRevision.isNullOrBlank()) {
                      return@WorkflowEventHandler runtimeEventFailure(
                          FailureCode.INVARIANT_VIOLATION,
                          "통합 결과에 정확한 main revision이 없습니다.",
                          merged.data.subTask.id,
                      )
                    }
                    val cleanup =
                        postMergeCleanup.execute(
                            PostMergeCleanupRequest(mergedSubTaskId = merged.data.subTask.id)
                        )
                    if (!providerConfigured) {
                      return@WorkflowEventHandler when (cleanup) {
                        is WorkflowResult.Success ->
                            WorkflowResult.Success(
                                MergeQueueEventResponse(merged.data, cleanup.data),
                                next = cleanup.next,
                            )
                        is WorkflowResult.Failure ->
                            WorkflowResult.Success(
                                MergeQueueEventResponse(
                                    merged.data,
                                    cleanup.data.toBlockedCleanup(merged.data.subTask.id),
                                ),
                                next = retryCleanupNext(),
                            )
                      }
                    }
                    val deployment =
                        deploymentLifecycle.create(
                            DeploymentLifecycleRequest(
                                mainRevision = mainRevision,
                                requestId = event.id,
                            )
                        )
                    if (deployment is WorkflowResult.Failure) {
                      return@WorkflowEventHandler deployment.withCleanupRetry(
                          cleanup,
                          merged.data.subTask.id,
                      )
                    }
                    val deploymentSuccess =
                        deployment as WorkflowResult.Success<DeploymentLifecycleResponse>
                    when (cleanup) {
                      is WorkflowResult.Success ->
                          WorkflowResult.Success(
                              MergeQueueEventResponse(
                                  merged.data,
                                  cleanup.data,
                                  deploymentSuccess.data,
                              ),
                              next = cleanup.next + deploymentSuccess.next,
                          )
                      is WorkflowResult.Failure ->
                          WorkflowResult.Success(
                              MergeQueueEventResponse(
                                  merged.data,
                                  cleanup.data.toBlockedCleanup(merged.data.subTask.id),
                                  deploymentSuccess.data,
                              ),
                              next = retryCleanupNext() + deploymentSuccess.next,
                          )
                    }
                  }
                }
              },
          ExternalEventKind.MAIN_MERGED to
              WorkflowEventHandler { event ->
                if (!providerConfigured) {
                  return@WorkflowEventHandler postMergeCleanup.execute(
                      PostMergeCleanupRequest(mergedSubTaskId = event.targetId)
                  )
                }
                val mainRevision =
                    snapshot(store)
                        .integrations
                        .firstOrNull { it.subTaskId == event.targetId }
                        ?.mainRevision
                        ?.takeIf(String::isNotBlank)
                        ?: event.attributes.optionalAttribute("main_revision")
                if (mainRevision == null) {
                  return@WorkflowEventHandler runtimeEventFailure(
                      FailureCode.INVALID_ARGUMENT,
                      "MAIN_MERGED 이벤트에 정확한 main revision이 없습니다.",
                      event.targetId,
                  )
                }
                val cleanup =
                    postMergeCleanup.execute(
                        PostMergeCleanupRequest(mergedSubTaskId = event.targetId)
                    )
                val deployment =
                    deploymentLifecycle.create(
                        DeploymentLifecycleRequest(
                            mainRevision = mainRevision,
                            requestId = event.id,
                        )
                    )
                if (deployment is WorkflowResult.Failure) {
                  return@WorkflowEventHandler deployment.withCleanupRetry(cleanup, event.targetId)
                }
                when (cleanup) {
                  is WorkflowResult.Success -> deployment
                  is WorkflowResult.Failure -> {
                    val deployed = deployment as WorkflowResult.Success<DeploymentLifecycleResponse>
                    WorkflowResult.Success(deployed.data, next = retryCleanupNext() + deployed.next)
                  }
                }
              },
      )
  if (providerConfigured) {
    handlers[ExternalEventKind.DEPLOYMENT_CHANGED] = WorkflowEventHandler { event ->
      handleDeploymentEvent(event, deploymentLifecycle, releaseLifecycle, store)
    }
    handlers[ExternalEventKind.CANARY_CHANGED] = WorkflowEventHandler { event ->
      handleDeploymentEvent(event, deploymentLifecycle, releaseLifecycle, store)
    }
    handlers[ExternalEventKind.RELEASE_CHANGED] = WorkflowEventHandler { event ->
      val stateName = event.attributes.optionalAttribute("state")
      if (stateName == null) {
        return@WorkflowEventHandler runtimeEventFailure(
            FailureCode.INVALID_ARGUMENT,
            "RELEASE_CHANGED 이벤트에 state가 없습니다.",
            event.targetId,
        )
      }
      val state =
          try {
            ReleaseState.valueOf(stateName.uppercase())
          } catch (_: IllegalArgumentException) {
            return@WorkflowEventHandler runtimeEventFailure(
                FailureCode.INVALID_ARGUMENT,
                "RELEASE_CHANGED 이벤트의 state가 올바르지 않습니다: $stateName",
                event.targetId,
            )
          }
      handleReleaseEvent(event, state, releaseLifecycle)
    }
  }
  return WorkflowEventUseCases(eventPort = eventPort, handlers = handlers)
}

/** 배포 상태를 반영하고 Production 이후 Release와 다음 후보를 순서대로 처리합니다. */
private fun handleDeploymentEvent(
    event: ExternalEvent,
    deploymentLifecycle: DeploymentLifecycleUseCase,
    releaseLifecycle: ReleaseLifecycleUseCases,
    store: WorkflowStorePort,
): WorkflowResult<DeploymentEventResponse> =
    when (val deployment = deploymentLifecycle.handle(event)) {
      is WorkflowResult.Failure -> deployment
      is WorkflowResult.Success ->
          handleProductionDeployment(
              event,
              deployment,
              releaseLifecycle,
              store,
              deploymentLifecycle,
          )
    }

/** Production 후보의 Release와 후속 배포 후보를 처리합니다. */
private fun handleProductionDeployment(
    event: ExternalEvent,
    deployment: WorkflowResult.Success<DeploymentLifecycleResponse>,
    releaseLifecycle: ReleaseLifecycleUseCases,
    store: WorkflowStorePort,
    deploymentLifecycle: DeploymentLifecycleUseCase,
): WorkflowResult<DeploymentEventResponse> {
  val deploymentData = deployment.data
  if (deploymentData.candidate.state != DeploymentCandidateState.PRODUCTION) {
    return WorkflowResult.Success(DeploymentEventResponse(deploymentData), next = deployment.next)
  }
  val productionEvent =
      DeploymentRecorded(
          targetId = deploymentData.candidate.id,
          state = deploymentData.candidate.state,
          occurredAtEpochMillis = event.occurredAtEpochMillis,
      )
  return when (val releases = releaseLifecycle.handleDeploymentAll(productionEvent)) {
    is WorkflowResult.Failure -> releases.withRuntimeNext(deployment.next)
    is WorkflowResult.Success -> {
      val currentNext = mergeNextActions(deployment.next, releases.data.flatMap { it.next })
      when (
          val nextCandidate =
              createNextCandidateAfterProduction(
                  store,
                  deploymentLifecycle,
                  deploymentData.candidate,
                  event.id,
              )
      ) {
        is WorkflowResult.Failure -> nextCandidate.withRuntimeNext(currentNext)
        is WorkflowResult.Success ->
            WorkflowResult.Success(
                DeploymentEventResponse(deploymentData, releases.data, nextCandidate.data),
                next = mergeNextActions(currentNext, nextCandidate.next),
            )
      }
    }
  }
}

/** Release 상태를 반영한 뒤 rollout 상태에서만 후속 동작을 실행합니다. */
private fun handleReleaseEvent(
    event: ExternalEvent,
    state: ReleaseState,
    releaseLifecycle: ReleaseLifecycleUseCases,
): WorkflowResult<ReleaseLifecycleResponse> =
    when (
        val handled =
            releaseLifecycle.handleRelease(
                ReleaseRecorded(event.targetId, state, event.occurredAtEpochMillis)
            )
    ) {
      is WorkflowResult.Failure -> handled
      is WorkflowResult.Success -> continueReleaseEvent(handled, event, releaseLifecycle)
    }

/** ROLLOUT을 진행하고 RELEASED 결과는 같은 Event 흐름에서 cleanup까지 처리합니다. */
private fun continueReleaseEvent(
    handled: WorkflowResult.Success<ReleaseLifecycleResponse>,
    event: ExternalEvent,
    releaseLifecycle: ReleaseLifecycleUseCases,
): WorkflowResult<ReleaseLifecycleResponse> {
  if (
      handled.data.release.state != ReleaseState.ROLLOUT &&
          handled.data.release.state != ReleaseState.RELEASED
  ) {
    return handled
  }
  val continued =
      when (
          val result =
              releaseLifecycle.continueRollout(
                  ContinueRolloutLifecycleRequest(event.targetId, event.id)
              )
      ) {
        is WorkflowResult.Failure -> return result.withRuntimeNext(handled.next)
        is WorkflowResult.Success -> result
      }
  val afterContinueNext = mergeNextActions(handled.next, continued.next)
  if (continued.data.release.state != ReleaseState.RELEASED) {
    return WorkflowResult.Success(continued.data, next = afterContinueNext)
  }
  return when (
      val cleaned =
          releaseLifecycle.continueRollout(
              ContinueRolloutLifecycleRequest(event.targetId, "${event.id}-cleanup")
          )
  ) {
    is WorkflowResult.Failure -> cleaned.withRuntimeNext(afterContinueNext)
    is WorkflowResult.Success ->
        WorkflowResult.Success(
            cleaned.data,
            next = mergeNextActions(afterContinueNext, cleaned.next),
        )
  }
}

/** 기존 next action을 중복 없이 이어 붙입니다. */
private fun mergeNextActions(vararg actions: List<NextAction>): List<NextAction> =
    actions.asList().flatten().distinctBy { Triple(it.actor, it.action, it.command) }

/** 앞선 런타임 단계의 next action을 후속 실패 결과에도 보존합니다. */
private fun WorkflowResult.Failure.withRuntimeNext(
    previous: List<NextAction>
): WorkflowResult.Failure =
    WorkflowResult.Failure(data.copy(next = mergeNextActions(previous, data.next)))

/** 이벤트 속성에서 공백 값과 누락 값을 동일한 선택적 값으로 변환합니다. */
private fun Map<String, String>.optionalAttribute(name: String): String? =
    this[name]?.trim()?.takeIf(String::isNotBlank)

/** 재시도 가능한 cleanup 동작을 구성합니다. */
private fun retryCleanupNext(): List<NextAction> =
    listOf(NextAction(io.springkit.workflow.domain.ActorKind.WORKFLOW, "retry_cleanup"))

/** 이벤트 handler가 재시도할 수 있는 명시적인 실패 결과를 만듭니다. */
private fun runtimeEventFailure(
    code: FailureCode,
    message: String,
    target: String,
): WorkflowResult.Failure =
    WorkflowResult.Failure(
        FailureData(code, message, blockedBy = listOf(BlockedBy(code.name, message, target)))
    )

/** 이미 완료된 통합을 되돌리지 않고 cleanup 실패를 재시도 가능한 차단 결과로 변환합니다. */
private fun io.springkit.workflow.domain.FailureData.toBlockedCleanup(
    mergedSubTaskId: String
): PostMergeCleanupResponse =
    PostMergeCleanupResponse(
        mergedSubTaskId = mergedSubTaskId,
        state = io.springkit.workflow.application.PostMergeCleanupState.BLOCKED,
        blocks =
            if (blockedBy.isEmpty()) {
              listOf(
                  io.springkit.workflow.application.PostMergeCleanupBlock(
                      "cleanup",
                      mergedSubTaskId,
                      code.name,
                      message,
                  )
              )
            } else {
              blockedBy.map {
                io.springkit.workflow.application.PostMergeCleanupBlock(
                    "cleanup",
                    it.target ?: mergedSubTaskId,
                    it.code,
                    it.message,
                )
              }
            },
    )
