package io.springkit.workflow.runtime

import io.springkit.workflow.adapter.cli.WorkflowCommandGateway
import io.springkit.workflow.application.AcknowledgeEventRequest
import io.springkit.workflow.application.AcknowledgeEventResponse
import io.springkit.workflow.application.DeploymentLifecycleUseCase
import io.springkit.workflow.application.MergeQueueLifecycleResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PostMergeCleanupResponse
import io.springkit.workflow.application.PostMergeCleanupUseCase
import io.springkit.workflow.application.ReceiveEventRequest
import io.springkit.workflow.application.ReceiveEventResponse
import io.springkit.workflow.application.ReleaseLifecycleUseCases
import io.springkit.workflow.application.WorkflowEventPort
import io.springkit.workflow.application.WorkflowEventUseCases

/*
 * Merge Queue 이벤트의 통합 결과와 후속 cleanup 결과입니다.
 */
data class MergeQueueEventResponse(
    val merge: MergeQueueLifecycleResponse,
    val cleanup: PostMergeCleanupResponse,
    val deployment: io.springkit.workflow.application.DeploymentLifecycleResponse? = null,
)

/*
 * 기본 Workflow 실행에 필요한 인바운드 명령과 외부 이벤트 조합입니다.
 */
data class WorkflowApplicationRuntime(
    val commandGateway: WorkflowCommandGateway,
    val eventUseCases: WorkflowEventUseCases,
    val postMergeCleanup: PostMergeCleanupUseCase,
    val deploymentLifecycle: DeploymentLifecycleUseCase,
    val releaseLifecycle: ReleaseLifecycleUseCases,
)

/*
 * 외부 이벤트 ingress가 아직 구성되지 않은 기본 실행 환경의 명시적 포트입니다.
 */
object UnconfiguredWorkflowEventPort : WorkflowEventPort {
  override fun receive(request: ReceiveEventRequest): PortResult<ReceiveEventResponse> =
      PortResult.Failure(
          PortError(
              code = "EVENT_INGRESS_NOT_CONFIGURED",
              message = "외부 Workflow 이벤트 ingress가 구성되지 않았습니다.",
              target = request.event.id,
          )
      )

  override fun acknowledge(request: AcknowledgeEventRequest): PortResult<AcknowledgeEventResponse> =
      PortResult.Failure(
          PortError(
              code = "EVENT_INGRESS_NOT_CONFIGURED",
              message = "외부 Workflow 이벤트 ingress가 구성되지 않았습니다.",
              target = request.eventId,
          )
      )
}
