package io.springkit.workflow.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.EventLog
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.MergeRecorded
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkflowState
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkflowApplicationRuntimeTest :
    FunSpec({
      context("기본 런타임으로 이벤트를 처리할 때") {
        test("MERGE_QUEUE_CHANGED 이벤트를 수신하면, 통합과 후속 cleanup에 연결합니다") {
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
                  providerRevision = MERGE_QUEUE_HEAD_SHA,
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
                  providerRevision = MERGE_QUEUE_HEAD_SHA,
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

        test("provider가 구성되면, DEPLOYMENT_CHANGED 이벤트를 Deployment lifecycle로 전달합니다") {
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

        test("provider가 미구성되면, DEPLOYMENT_CHANGED 이벤트를 지원하지 않습니다") {
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

        test("Production 후보가 여러 Feature Flag를 포함하면, Release를 모두 생성합니다") {
          val root = Files.createTempDirectory("workflow-runtime-release-all-")
          val statePath = root.resolve("state.json")
          val state = productionState(featureFlagIds = listOf("flag-1", "flag-2"))
          Files.writeString(statePath, WorkflowStateJsonCodec.encodeToString(state))
          val runner = RuntimeProviderCommandRunner(root)
          val eventPort = RecordingWorkflowEventPort()
          val runtime = providerRuntime(root, statePath, runner, eventPort)

          val result =
              runtime.eventUseCases.handle(
                  ReceiveEventRequest(
                      ExternalEvent(
                          id = "deployment-production-1",
                          kind = ExternalEventKind.DEPLOYMENT_CHANGED,
                          targetId = "candidate-1",
                          occurredAtEpochMillis = 1,
                      )
                  )
              )

          result.shouldBeInstanceOf<WorkflowResult.Success<*>>()
          eventPort.acknowledged?.accepted shouldBe true
          runner.operationCalls("create-release") shouldBe 2
          runner.operationCalls("validate-release") shouldBe 2
          WorkflowStateJsonCodec.decode(Files.readString(statePath)).releases.values.map {
            it.featureFlagId
          } shouldBe listOf("flag-1", "flag-2")
        }

        test("RELEASE_CHANGED가 ROLLOUT이면, Release provider의 rollout을 계속 실행합니다") {
          val root = Files.createTempDirectory("workflow-runtime-release-rollout-")
          val statePath = root.resolve("state.json")
          val state =
              productionState(
                  featureFlagIds = listOf("flag-1"),
                  releaseState = ReleaseState.ROLLOUT,
              )
          Files.writeString(statePath, WorkflowStateJsonCodec.encodeToString(state))
          val runner = RuntimeProviderCommandRunner(root)
          val eventPort = RecordingWorkflowEventPort()
          val runtime = providerRuntime(root, statePath, runner, eventPort)

          val result =
              runtime.eventUseCases.handle(
                  ReceiveEventRequest(
                      ExternalEvent(
                          id = "release-rollout-1",
                          kind = ExternalEventKind.RELEASE_CHANGED,
                          targetId = "release-1",
                          occurredAtEpochMillis = 2,
                          attributes = mapOf("state" to "ROLLOUT"),
                      )
                  )
              )

          result.shouldBeInstanceOf<WorkflowResult.Success<*>>()
          runner.operationCalls("continue-rollout") shouldBe 1
          WorkflowStateJsonCodec.decode(Files.readString(statePath))
              .releases["release-1"]
              ?.state shouldBe ReleaseState.CLEANUP_REQUIRED
        }

        test("Production 전환 뒤 후속 merge가 있으면, 최신 main revision으로 다음 후보를 만듭니다") {
          val root = Files.createTempDirectory("workflow-runtime-next-candidate-")
          val statePath = root.resolve("state.json")
          val state = productionState(featureFlagIds = emptyList(), includeNextMerge = true)
          Files.writeString(statePath, WorkflowStateJsonCodec.encodeToString(state))
          val runner = RuntimeProviderCommandRunner(root)
          val eventPort = RecordingWorkflowEventPort()
          val runtime = providerRuntime(root, statePath, runner, eventPort)
          val event =
              ExternalEvent(
                  id = "deployment-production-2",
                  kind = ExternalEventKind.CANARY_CHANGED,
                  targetId = "candidate-1",
                  occurredAtEpochMillis = 3,
                  attributes = mapOf("outcome" to "SUCCEEDED"),
              )

          runtime.eventUseCases.handle(ReceiveEventRequest(event))
          runtime.eventUseCases.handle(
              ReceiveEventRequest(event.copy(id = "deployment-production-3"))
          )

          val stored = WorkflowStateJsonCodec.decode(Files.readString(statePath))
          stored.deploymentCandidates.values.map { it.mainRevision } shouldContain "main-2"
          runner.operationCalls("create-candidate") shouldBe 1
        }

        test("Production 뒤 event log가 일부만 남으면, integrations에서 후속 merge를 찾아 후보를 만듭니다") {
          val root = Files.createTempDirectory("workflow-runtime-partial-event-log-")
          val statePath = root.resolve("state.json")
          val state =
              productionState(
                  featureFlagIds = emptyList(),
                  includeNextMerge = true,
                  partialEventLog = true,
              )
          Files.writeString(statePath, WorkflowStateJsonCodec.encodeToString(state))
          val runner = RuntimeProviderCommandRunner(root)
          val runtime = providerRuntime(root, statePath, runner, RecordingWorkflowEventPort())

          val result =
              runtime.eventUseCases.handle(
                  ReceiveEventRequest(
                      ExternalEvent(
                          id = "deployment-production-partial-1",
                          kind = ExternalEventKind.DEPLOYMENT_CHANGED,
                          targetId = "candidate-1",
                          occurredAtEpochMillis = 4,
                      )
                  )
              )

          result.shouldBeInstanceOf<WorkflowResult.Success<*>>()
          WorkflowStateJsonCodec.decode(Files.readString(statePath))
              .deploymentCandidates
              .values
              .map { it.mainRevision } shouldContain "main-2"
          runner.operationCalls("create-candidate") shouldBe 1
        }
      }
    })

private fun providerRuntime(
    root: Path,
    statePath: Path,
    runner: RuntimeProviderCommandRunner,
    eventPort: WorkflowEventPort,
): WorkflowApplicationRuntime =
    createDefaultApplicationRuntime(
        currentDirectory = root,
        environment =
            mapOf(
                "WORKFLOW_REPO_ROOT" to root.toString(),
                "WORKFLOW_STATE_FILE" to statePath.toString(),
                "WORKFLOW_DEPLOYMENT_COMMAND" to "[\"deploy\"]",
                "WORKFLOW_RELEASE_COMMAND" to "[\"release\"]",
                "WORKFLOW_FEATURE_FLAG_COMMAND" to "[\"feature\"]",
            ),
        commandRunner = runner,
        eventPort = eventPort,
    )

private fun productionState(
    featureFlagIds: List<String>,
    releaseState: ReleaseState? = null,
    includeNextMerge: Boolean = false,
    partialEventLog: Boolean = false,
): WorkflowState {
  val currentSubTasks = featureFlagIds.mapIndexed { index, featureFlagId ->
    SubTask(
        id = "sk-flag-${index + 1}",
        taskId = "task-1",
        title = "기능 플래그 ${index + 1}",
        state = SubTaskState.MERGED,
        exposure = Exposure.FEATURE_FLAG,
        featureFlagId = featureFlagId,
    )
  }
  val currentSubTask =
      if (currentSubTasks.isNotEmpty()) {
        currentSubTasks
      } else {
        listOf(
            SubTask(
                id = "sk-current",
                taskId = "task-1",
                title = "현재 변경",
                state = SubTaskState.MERGED,
            )
        )
      }
  val nextSubTask =
      if (includeNextMerge) {
        SubTask(
            id = "sk-next",
            taskId = "task-1",
            title = "다음 변경",
            state = SubTaskState.MERGED,
        )
      } else {
        null
      }
  val allSubTasks = currentSubTask + listOfNotNull(nextSubTask)
  val task =
      Task(
          id = "task-1",
          externalId = ExternalTaskId("TASK-1"),
          title = "배포 작업",
          subTaskIds = allSubTasks.map { it.id },
      )
  val candidate =
      DeploymentCandidate(
          id = "candidate-1",
          mainRevision = "main-1",
          includedSubTasks = currentSubTask.map { it.id },
          validations = listOf(Validation("validation-1", "검증", ValidationStatus.PASSED)),
          state = DeploymentCandidateState.PRODUCTION,
      )
  val release = releaseState?.let {
    Release(
        id = "release-1",
        candidateId = candidate.id,
        featureFlagId = requireNotNull(featureFlagIds.singleOrNull()),
        state = it,
        productionReady = true,
        internalValidationPassed = true,
    )
  }
  val merges =
      listOf(MergeRecorded(currentSubTask.first().id, "main-1")) +
          if (includeNextMerge && !partialEventLog) {
            listOf(MergeRecorded("sk-next", "main-2"))
          } else {
            emptyList()
          }
  val integrations =
      currentSubTask
          .map { Integration(it.id, IntegrationState.MERGED, mainRevision = "main-1") }
          .plus(
              if (includeNextMerge) {
                listOf(Integration("sk-next", IntegrationState.MERGED, mainRevision = "main-2"))
              } else {
                emptyList()
              }
          )
  return WorkflowState(
      tasks = mapOf(task.id to task),
      subTasks = allSubTasks.associateBy(SubTask::id),
      deploymentCandidates = mapOf(candidate.id to candidate),
      releases = release?.let { mapOf(it.id to it) }.orEmpty(),
      integrations = integrations.associateBy(Integration::subTaskId),
      eventLog = EventLog(events = merges),
  )
}

private class RuntimeProviderCommandRunner(private val repositoryRoot: Path) : CommandRunner {
  private data class CandidatePayload(
      val id: String,
      val mainRevision: String,
      val includedSubTasks: String,
      val risks: String,
  )

  private val commands = mutableListOf<List<String>>()
  private val candidates = mutableMapOf<String, CandidatePayload>()
  private val releases = mutableMapOf<String, Pair<String, String>>()

  fun operationCalls(operation: String): Int = commands.count { it.getOrNull(1) == operation }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    if (command == listOf("git", "rev-parse", "--show-toplevel")) {
      return CommandResult(0, repositoryRoot.toString() + "\n", "")
    }
    commands += command
    return when {
      command.firstOrNull() == "deploy" && command.getOrNull(1) == "create-candidate" ->
          createCandidate(command)
      command.firstOrNull() == "deploy" && command.getOrNull(1) == "validate-candidate" ->
          validateCandidate(command)
      command.firstOrNull() == "release" && command.getOrNull(1) == "create-release" ->
          release(command, ReleaseState.SAFE_DEFAULT)
      command.firstOrNull() == "release" && command.getOrNull(1) == "validate-release" ->
          release(command, ReleaseState.AWAITING_RELEASE_APPROVAL, true)
      command.firstOrNull() == "release" && command.getOrNull(1) == "continue-rollout" ->
          release(command, ReleaseState.RELEASED, true)
      else -> CommandResult(0, "{}", "")
    }
  }

  private fun createCandidate(command: List<String>): CommandResult {
    val payload = Json.parseToJsonElement(command.last()).jsonObject
    val revision = payload.getValue("main_revision").jsonPrimitive.content
    val candidate =
        CandidatePayload(
            id = "candidate-$revision",
            mainRevision = revision,
            includedSubTasks = payload.getValue("included_subtasks").jsonArray.toString(),
            risks = payload.getValue("risks").jsonObject.toString(),
        )
    candidates[candidate.id] = candidate
    return CommandResult(0, candidateResponse(candidate, DeploymentCandidateState.CANDIDATE), "")
  }

  private fun validateCandidate(command: List<String>): CommandResult {
    val payload = Json.parseToJsonElement(command.last()).jsonObject
    val id = payload.getValue("candidate_id").jsonPrimitive.content
    return CommandResult(
        0,
        candidateResponse(requireNotNull(candidates[id]), DeploymentCandidateState.CANDIDATE),
        "",
    )
  }

  private fun candidateResponse(
      candidate: CandidatePayload,
      state: DeploymentCandidateState,
  ): String =
      """{"candidate":{"id":"${candidate.id}","main_revision":"${candidate.mainRevision}","included_subtasks":${candidate.includedSubTasks},"risks":${candidate.risks},"state":"${state.name}"}}"""

  private fun release(
      command: List<String>,
      state: ReleaseState,
      validated: Boolean = false,
  ): CommandResult {
    val payload = Json.parseToJsonElement(command.last()).jsonObject
    val releaseId = payload.getValue("release_id").jsonPrimitive.content
    val target =
        if (command.getOrNull(1) == "create-release") {
          val value =
              payload.getValue("candidate_id").jsonPrimitive.content to
                  payload.getValue("feature_flag_id").jsonPrimitive.content
          releases[releaseId] = value
          value
        } else {
          releases[releaseId] ?: ("candidate-1" to "flag-1")
        }
    val candidateId = target.first
    val featureFlagId = target.second
    return CommandResult(
        0,
        """{"release":{"release_id":"$releaseId","candidate_id":"$candidateId","feature_flag_id":"$featureFlagId","state":"${state.name}","production_ready":$validated,"internal_validation_passed":$validated}}""",
        "",
    )
  }
}

private const val MERGE_QUEUE_HEAD_SHA = "0123456789abcdef0123456789abcdef01234567"

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
              {"number":17,"state":"MERGED","headRefName":"sk-101","headRefOid":"$MERGE_QUEUE_HEAD_SHA","mergeCommit":{"oid":"main-1"}}
              """
                  .trimIndent()
            } else {
              """
              {"number":17,"state":"OPEN","mergeStateStatus":"CLEAN","headRefName":"sk-101","headRefOid":"$MERGE_QUEUE_HEAD_SHA","statusCheckRollup":[],"isInMergeQueue":false}
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
