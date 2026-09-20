package io.springkit.workflow.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.adapter.store.WorkflowStateJsonCodec
import io.springkit.workflow.application.AcknowledgeEventRequest
import io.springkit.workflow.application.AcknowledgeEventResponse
import io.springkit.workflow.application.ExternalEvent
import io.springkit.workflow.application.ExternalEventKind
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReceiveEventRequest
import io.springkit.workflow.application.ReceiveEventResponse
import io.springkit.workflow.application.WorkflowEventPort
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkflowState
import java.nio.file.Files
import java.nio.file.Path

class WorkflowApplicationRuntimeTest :
    FunSpec({
      context("기본 런타임으로 이벤트를 처리할 때") {
        test("MERGE_QUEUE_CHANGED 이벤트를 통합과 후속 cleanup에 연결합니다") {
          val root = Files.createTempDirectory("workflow-runtime-merge-event-")
          val statePath = root.resolve("state.json")
          val subTaskId = "sk-101"
          val pullRequestId = "17"
          val task =
              Task(
                  id = "task-1",
                  externalId = ExternalTaskId("TASK-1"),
                  title = "작업",
                  subTaskIds = listOf(subTaskId),
              )
          val subTask =
              SubTask(
                  id = subTaskId,
                  taskId = task.id,
                  title = "변경",
                  state = SubTaskState.QUEUED,
                  pullRequestId = pullRequestId,
              )
          val changeRevision =
              ChangeRevision(
                  id = "change-1",
                  number = 1,
                  diff = Diff(identity = "diff-1"),
              )
          val pullRequest =
              PullRequest(
                  id = pullRequestId,
                  subTaskId = subTaskId,
                  title = "[SK-101] 변경",
                  body = "변경 내용",
                  base = "main",
                  state = PullRequestState.QUEUED,
                  reviewRevision = ReviewRevision("review-1", 1, "리뷰 본문"),
                  changeRevision = changeRevision,
              )
          val mergeQueue =
              MergeQueueEntry(
                  id = "queue-1",
                  subTaskId = subTaskId,
                  pullRequestId = pullRequest.id,
                  changeRevisionId = changeRevision.id,
                  state = MergeQueueState.PASSED,
              )
          Files.writeString(
              statePath,
              WorkflowStateJsonCodec.encodeToString(
                  WorkflowState(
                      tasks = mapOf(task.id to task),
                      subTasks = mapOf(subTask.id to subTask),
                      pullRequests = mapOf(pullRequest.id to pullRequest),
                      mergeQueue = mapOf(mergeQueue.id to mergeQueue),
                  )
              ),
          )
          val runner = MergeQueueEventCommandRunner(root)
          val eventPort = RecordingWorkflowEventPort()
          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory = root,
                  environment =
                      mapOf(
                          "WORKFLOW_REPO_ROOT" to root.toString(),
                          "WORKFLOW_STATE_FILE" to statePath.toString(),
                      ),
                  commandRunner = runner,
                  eventPort = eventPort,
              )
          val event =
              ExternalEvent(
                  id = "merge-event-1",
                  kind = ExternalEventKind.MERGE_QUEUE_CHANGED,
                  targetId = subTaskId,
                  occurredAtEpochMillis = 1,
                  attributes =
                      mapOf(
                          "merge_queue_entry_id" to mergeQueue.id,
                          "change_revision_id" to changeRevision.id,
                      ),
              )

          val result = runtime.eventUseCases.handle(ReceiveEventRequest(event))

          result.shouldBeInstanceOf<WorkflowResult.Success<*>>()
          eventPort.acknowledged shouldBe AcknowledgeEventRequest(event.id, true, null)
          runner.mergeCalls shouldBe 1
          val stored = WorkflowStateJsonCodec.decode(Files.readString(statePath))
          stored.subTasks[subTaskId]?.state shouldBe SubTaskState.MERGED
          stored.pullRequests[pullRequest.id]?.state shouldBe PullRequestState.MERGED
          stored.integrations[subTaskId]?.mainRevision shouldBe "main-1"
          stored.deploymentCandidates shouldBe emptyMap()
        }

        test("MAIN_MERGED 이벤트를 수신하면, 병합 후 정리 유스케이스로 연결합니다") {
          val eventPort = RecordingWorkflowEventPort()
          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory = Files.createTempDirectory("workflow-runtime-event-"),
                  environment = emptyMap(),
                  commandRunner = RuntimeCommandRunnerForComposition,
                  eventPort = eventPort,
              )

          val result =
              runtime.eventUseCases.handle(
                  ReceiveEventRequest(
                      ExternalEvent(
                          id = "merge-event-1",
                          kind = ExternalEventKind.MAIN_MERGED,
                          targetId = "sk-101",
                          occurredAtEpochMillis = 1,
                      )
                  )
              )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()
          failure.data.code.name shouldBe "SUBTASK_NOT_FOUND"
          eventPort.acknowledged shouldBe
              AcknowledgeEventRequest("merge-event-1", false, failure.data.message)
        }

        test("AI_REVIEW_CHANGED 이벤트를 수신하면, INVALID_ARGUMENT를 반환합니다") {
          val eventPort = RecordingWorkflowEventPort()
          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory = Files.createTempDirectory("workflow-runtime-ai-event-"),
                  environment = emptyMap(),
                  commandRunner = RuntimeCommandRunnerForComposition,
                  eventPort = eventPort,
              )

          val result =
              runtime.eventUseCases.handle(
                  ReceiveEventRequest(
                      ExternalEvent(
                          id = "ai-event-1",
                          kind = ExternalEventKind.AI_REVIEW_CHANGED,
                          targetId = "sk-101",
                          occurredAtEpochMillis = 1,
                      )
                  )
              )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()
          failure.data.code.name shouldBe "INVALID_ARGUMENT"
          eventPort.acknowledged?.accepted shouldBe false
        }

        test("provider가 구성되면 DEPLOYMENT_CHANGED 이벤트를 Deployment lifecycle로 전달합니다") {
          val root = Files.createTempDirectory("workflow-runtime-deployment-event-")
          val eventPort = RecordingWorkflowEventPort()
          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory = root,
                  environment =
                      mapOf(
                          "WORKFLOW_REPO_ROOT" to root.toString(),
                          "WORKFLOW_DEPLOYMENT_COMMAND" to "[\"deploy\"]",
                          "WORKFLOW_RELEASE_COMMAND" to "[\"release\"]",
                          "WORKFLOW_FEATURE_FLAG_COMMAND" to "[\"feature\"]",
                      ),
                  commandRunner = RuntimeCommandRunnerForComposition,
                  eventPort = eventPort,
              )

          val event =
              ExternalEvent(
                  id = "deployment-event-1",
                  kind = ExternalEventKind.DEPLOYMENT_CHANGED,
                  targetId = "candidate-1",
                  occurredAtEpochMillis = 1,
              )
          val result = runtime.eventUseCases.handle(ReceiveEventRequest(event))

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()
          failure.data.code.name shouldBe "DEPLOYMENT_NOT_FOUND"
          eventPort.acknowledged shouldBe
              AcknowledgeEventRequest(event.id, false, failure.data.message)
        }

        test("provider가 미구성되면 DEPLOYMENT_CHANGED 이벤트를 지원하지 않습니다") {
          val eventPort = RecordingWorkflowEventPort()
          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory =
                      Files.createTempDirectory("workflow-runtime-deployment-disabled-"),
                  environment = emptyMap(),
                  commandRunner = RuntimeCommandRunnerForComposition,
                  eventPort = eventPort,
              )
          val event =
              ExternalEvent(
                  id = "deployment-event-2",
                  kind = ExternalEventKind.DEPLOYMENT_CHANGED,
                  targetId = "candidate-1",
                  occurredAtEpochMillis = 1,
              )

          val result = runtime.eventUseCases.handle(ReceiveEventRequest(event))

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code.name shouldBe
              "INVALID_ARGUMENT"
          eventPort.acknowledged?.accepted shouldBe false
        }
      }
    })

private object RuntimeCommandRunnerForComposition : CommandRunner {
  override fun run(command: List<String>, workingDirectory: Path): CommandResult =
      if (command == listOf("git", "rev-parse", "--show-toplevel")) {
        CommandResult(0, workingDirectory.toString() + "\n", "")
      } else {
        CommandResult(0, "", "")
      }
}

private class RecordingWorkflowEventPort : WorkflowEventPort {
  var acknowledged: AcknowledgeEventRequest? = null

  override fun receive(request: ReceiveEventRequest): PortResult<ReceiveEventResponse> =
      PortResult.Success(ReceiveEventResponse(request.event, accepted = true))

  override fun acknowledge(request: AcknowledgeEventRequest): PortResult<AcknowledgeEventResponse> {
    acknowledged = request
    return PortResult.Success(AcknowledgeEventResponse(request.eventId))
  }
}

private class MergeQueueEventCommandRunner(private val root: Path) : CommandRunner {
  var mergeCalls: Int = 0
    private set

  private var pullRequestViewCalls: Int = 0

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    if (command == listOf("git", "rev-parse", "--show-toplevel")) {
      return CommandResult(0, root.toString() + "\n", "")
    }
    if (command.firstOrNull() == "gh" && command.getOrNull(1) == "pr") {
      if (command.getOrNull(2) == "merge") {
        mergeCalls += 1
        return CommandResult(0, "", "")
      }
      if (command.getOrNull(2) == "view") {
        pullRequestViewCalls += 1
        return CommandResult(
            0,
            if (pullRequestViewCalls >= 3) {
              """
              {"number":17,"state":"MERGED","headRefName":"sk-101","headRefOid":"change-1","mergeCommit":{"oid":"main-1"}}
              """
                  .trimIndent()
            } else {
              """
              {"number":17,"state":"OPEN","mergeStateStatus":"CLEAN","headRefName":"sk-101","headRefOid":"change-1","statusCheckRollup":[],"isInMergeQueue":false}
              """
                  .trimIndent()
            },
            "",
        )
      }
    }
    if (command == listOf("git", "rev-parse", "refs/remotes/origin/main")) {
      return CommandResult(0, "main-1\n", "")
    }
    return CommandResult(0, "", "")
  }
}
