package io.springkit.workflow.application

import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult

/** 외부 이벤트 하나를 처리하는 애플리케이션 handler 계약입니다. */
fun interface WorkflowEventHandler {
  fun handle(event: ExternalEvent): WorkflowResult<*>
}

/** 수신한 이벤트의 애플리케이션 처리 결과입니다. */
data class WorkflowEventResponse(
    val event: ExternalEvent,
    val duplicate: Boolean = false,
) {
  val handled: Boolean
    get() = !duplicate
}

/** 외부 이벤트를 수신하고, 한 번만 handler에 전달한 뒤 acknowledge합니다. */
class WorkflowEventUseCases(
    private val eventPort: WorkflowEventPort,
    private val handlers: Map<ExternalEventKind, WorkflowEventHandler> = emptyMap(),
) {
  /** 외부 이벤트를 처리합니다. */
  fun receive(request: ReceiveEventRequest): WorkflowResult<WorkflowEventResponse> =
      execute(request)

  /** 외부 이벤트 어댑터의 수신 계약을 애플리케이션 경계로 연결합니다. */
  fun handle(request: ReceiveEventRequest): WorkflowResult<WorkflowEventResponse> = execute(request)

  /** 외부 이벤트를 멱등적으로 처리하고 처리 결과를 acknowledge합니다. */
  fun execute(request: ReceiveEventRequest): WorkflowResult<WorkflowEventResponse> {
    return when (val received = eventPort.receive(request)) {
      is PortResult.Failure -> received.toEventFailure(FailureCode.EXTERNAL_FAILURE)
      is PortResult.Success -> process(received.value)
    }
  }

  private fun process(received: ReceiveEventResponse): WorkflowResult<WorkflowEventResponse> {
    val event = received.event
    if (!received.accepted) {
      return acknowledge(
          event = event,
          accepted = true,
          message = null,
          success = WorkflowResult.Success(WorkflowEventResponse(event, duplicate = true)),
      )
    }

    val handler = handlers[event.kind]
    if (event.kind == ExternalEventKind.AI_REVIEW_CHANGED || handler == null) {
      val failure =
          FailureData(
              code = FailureCode.INVALID_ARGUMENT,
              message = "지원하지 않는 외부 이벤트 종류입니다: ${event.kind.name}",
              blockedBy =
                  listOf(
                      BlockedBy(
                          code = "UNSUPPORTED_EVENT_KIND",
                          message = "등록된 이벤트 handler가 없습니다.",
                          target = event.kind.name,
                      )
                  ),
          )
      return acknowledge(
          event = event,
          accepted = false,
          message = failure.message,
          success = WorkflowResult.Failure(failure),
      )
    }

    val handled =
        try {
          handler.handle(event)
        } catch (failure: RuntimeException) {
          WorkflowResult.Failure(
              FailureData(
                  code = FailureCode.EXTERNAL_FAILURE,
                  message = failure.message ?: "이벤트 handler가 실패했습니다.",
                  blockedBy =
                      listOf(
                          BlockedBy(
                              code = FailureCode.EXTERNAL_FAILURE.name,
                              message = failure.message ?: "이벤트 handler가 실패했습니다.",
                              target = event.id,
                          )
                      ),
              )
          )
        }

    return when (handled) {
      is WorkflowResult.Success ->
          acknowledge(
              event = event,
              accepted = true,
              message = null,
              success = WorkflowResult.Success(WorkflowEventResponse(event)),
          )
      is WorkflowResult.Failure ->
          acknowledge(
              event = event,
              accepted = false,
              message = handled.data.message,
              success = handled,
          )
    }
  }

  private fun <T> acknowledge(
      event: ExternalEvent,
      accepted: Boolean,
      message: String?,
      success: WorkflowResult<T>,
  ): WorkflowResult<T> {
    return when (
        val acknowledged =
            eventPort.acknowledge(AcknowledgeEventRequest(event.id, accepted, message))
    ) {
      is PortResult.Success -> success
      is PortResult.Failure -> acknowledged.toEventFailure(FailureCode.EXTERNAL_FAILURE)
    }
  }
}

private fun PortResult.Failure.toEventFailure(fallback: FailureCode): WorkflowResult.Failure {
  val code = FailureCode.entries.firstOrNull { it.name == error.code } ?: fallback
  return WorkflowResult.Failure(
      FailureData(
          code = code,
          message = error.message,
          blockedBy =
              listOf(
                  BlockedBy(
                      code = error.code,
                      message = error.message,
                      target = error.target,
                  )
              ),
      )
  )
}
