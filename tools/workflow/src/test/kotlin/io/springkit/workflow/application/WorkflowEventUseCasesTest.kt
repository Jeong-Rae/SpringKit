package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult

class WorkflowEventUseCasesTest :
    FunSpec({
      context("수신한 이벤트가 중복 또는 수락 상태일 때") {
        test("중복 이벤트를 수신하면, 중복 성공 결과를 반환합니다") {
          val port =
              RecordingWorkflowEventPort(received = ReceiveEventResponse(event(), accepted = false))
          var calls = 0
          val useCase =
              WorkflowEventUseCases(
                  eventPort = port,
                  handlers =
                      mapOf(
                          ExternalEventKind.TASK_CHANGED to
                              WorkflowEventHandler {
                                calls += 1
                                WorkflowResult.Success(Unit)
                              }
                      ),
              )

          val result = useCase.handle(ReceiveEventRequest(event()))

          val success = result.shouldBeInstanceOf<WorkflowResult.Success<WorkflowEventResponse>>()
          success.data.duplicate shouldBe true
          calls shouldBe 0
          port.acknowledgements.single().accepted shouldBe true
        }

        test("수락된 이벤트를 수신하면, 등록된 handler를 한 번 호출합니다") {
          val port =
              RecordingWorkflowEventPort(received = ReceiveEventResponse(event(), accepted = true))
          val calls = mutableListOf<ExternalEvent>()
          val useCase =
              WorkflowEventUseCases(
                  eventPort = port,
                  handlers =
                      mapOf(
                          ExternalEventKind.TASK_CHANGED to
                              WorkflowEventHandler {
                                calls += it
                                WorkflowResult.Success(Unit)
                              }
                      ),
              )

          val result = useCase.receive(ReceiveEventRequest(event()))

          result.shouldBeInstanceOf<WorkflowResult.Success<WorkflowEventResponse>>()
          calls shouldBe listOf(event())
          port.acknowledgements.single().accepted shouldBe true
        }
      }

      context("등록된 handler의 처리 결과를 acknowledge할 때") {
        test("handler가 실패하면, 같은 실패 내용을 acknowledge합니다") {
          val port =
              RecordingWorkflowEventPort(received = ReceiveEventResponse(event(), accepted = true))
          val failure = FailureData(FailureCode.STATE_CONFLICT, "처리할 수 없는 상태입니다.")
          val useCase =
              WorkflowEventUseCases(
                  port,
                  mapOf(
                      ExternalEventKind.TASK_CHANGED to
                          WorkflowEventHandler { WorkflowResult.Failure(failure) }
                  ),
              )

          val result = useCase.handle(ReceiveEventRequest(event()))

          result shouldBe WorkflowResult.Failure(failure)
          port.acknowledgements.single().accepted shouldBe false
          port.acknowledgements.single().message shouldBe failure.message
        }

        test("acknowledge가 실패하면, EXTERNAL_FAILURE를 반환합니다") {
          val port =
              RecordingWorkflowEventPort(
                  received = ReceiveEventResponse(event(), accepted = true),
                  acknowledgeResult = PortResult.Failure(PortError("ACK_FAILED", "acknowledge 실패")),
              )
          val useCase =
              WorkflowEventUseCases(
                  port,
                  mapOf(
                      ExternalEventKind.TASK_CHANGED to
                          WorkflowEventHandler { WorkflowResult.Success(Unit) }
                  ),
              )

          val result = useCase.handle(ReceiveEventRequest(event()))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.EXTERNAL_FAILURE
          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.message shouldContain
              "acknowledge 실패"
        }
      }

      context("지원하지 않는 이벤트를 수신할 때") {
        test("AI_REVIEW_CHANGED를 수신하면, INVALID_ARGUMENT를 반환합니다") {
          val aiEvent = event(kind = ExternalEventKind.AI_REVIEW_CHANGED)
          val port =
              RecordingWorkflowEventPort(received = ReceiveEventResponse(aiEvent, accepted = true))
          var calls = 0
          val useCase =
              WorkflowEventUseCases(
                  port,
                  mapOf(
                      ExternalEventKind.AI_REVIEW_CHANGED to
                          WorkflowEventHandler {
                            calls += 1
                            WorkflowResult.Success(Unit)
                          }
                  ),
              )

          val result = useCase.handle(ReceiveEventRequest(aiEvent))

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.INVALID_ARGUMENT
          failure.message shouldContain ExternalEventKind.AI_REVIEW_CHANGED.name
          calls shouldBe 0
          port.acknowledgements.single().accepted shouldBe false
        }

        test("등록되지 않은 kind를 수신하면, INVALID_ARGUMENT를 반환합니다") {
          val received = event(kind = ExternalEventKind.REVIEW_CHANGED)
          val port =
              RecordingWorkflowEventPort(received = ReceiveEventResponse(received, accepted = true))
          val useCase = WorkflowEventUseCases(port)

          val result = useCase.handle(ReceiveEventRequest(received))

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>().data
          failure.code shouldBe FailureCode.INVALID_ARGUMENT
          failure.message shouldContain ExternalEventKind.REVIEW_CHANGED.name
          port.acknowledgements.single().accepted shouldBe false
        }
      }
    })

private fun event(
    id: String = "event-1",
    kind: ExternalEventKind = ExternalEventKind.TASK_CHANGED,
) = ExternalEvent(id = id, kind = kind, targetId = "target-1", occurredAtEpochMillis = 1)

private class RecordingWorkflowEventPort(
    private val received: ReceiveEventResponse,
    private val acknowledgeResult: PortResult<AcknowledgeEventResponse> =
        PortResult.Success(AcknowledgeEventResponse(received.event.id)),
) : WorkflowEventPort {
  val acknowledgements = mutableListOf<AcknowledgeEventRequest>()

  override fun receive(request: ReceiveEventRequest): PortResult<ReceiveEventResponse> =
      PortResult.Success(received)

  override fun acknowledge(request: AcknowledgeEventRequest): PortResult<AcknowledgeEventResponse> {
    acknowledgements += request
    return acknowledgeResult
  }
}
