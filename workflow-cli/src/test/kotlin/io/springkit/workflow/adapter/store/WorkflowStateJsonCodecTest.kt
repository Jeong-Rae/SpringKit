package io.springkit.workflow.adapter.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.springkit.workflow.adapter.json.WorkflowJson
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.EventLog
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.GateRecorded
import io.springkit.workflow.domain.GateType
import io.springkit.workflow.domain.IdSequence
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseState
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.StartRequestKey
import io.springkit.workflow.domain.StartRequestRecord
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.SyncConflict
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskState
import io.springkit.workflow.domain.ThreadState
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowState
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WorkflowStateJsonCodecTest :
    FunSpec({
      context("내구성 있는 모든 상태 영역을 JSON으로 인코딩하고 디코딩하면") {
        test("내구성 있는 모든 상태 영역을 JSON으로 인코딩하고 디코딩하면, 원래 상태와 일치하고 문자열과 바이트 배열 입력을 모두 지원합니다") {
          val state = fixture()

          val encoded = WorkflowStateJsonCodec.encodeToString(state)
          val decoded = WorkflowStateJsonCodec.decode(encoded)

          decoded shouldBe state
          WorkflowStateJsonCodec.decode(encoded.toByteArray()) shouldBe state
          WorkflowStateJsonCodec.encodeToString(decoded) shouldBe encoded
        }
      }

      context("같은 상태를 여러 번 JSON으로 인코딩하면") {
        test("같은 상태를 여러 번 JSON으로 인코딩하면, 항상 같은 문자열을 생성합니다") {
          val state = fixture()

          WorkflowStateJsonCodec.encodeToString(state) shouldBe
              WorkflowStateJsonCodec.encodeToString(state)
        }
      }

      context("지원하지 않는 schemaVersion으로 상태를 디코딩하면") {
        test("지원하지 않는 schemaVersion으로 상태를 디코딩하면, StateDecodeException을 발생시킵니다") {
          val encoded = WorkflowStateJsonCodec.encodeToString(fixture())

          shouldThrow<StateDecodeException> {
            WorkflowStateJsonCodec.decode(withRootField(encoded, "schemaVersion", JsonPrimitive(0)))
          }
        }
      }

      context("음수 storeRevision으로 상태를 디코딩하면") {
        test("음수 storeRevision으로 상태를 디코딩하면, StateDecodeException을 발생시킵니다") {
          val encoded = WorkflowStateJsonCodec.encodeToString(fixture())

          shouldThrow<StateDecodeException> {
            WorkflowStateJsonCodec.decode(
                withRootField(encoded, "storeRevision", JsonPrimitive(-1))
            )
          }
        }
      }

      context("숫자가 아닌 storeRevision으로 상태를 디코딩하면") {
        test("숫자가 아닌 storeRevision으로 상태를 디코딩하면, StateDecodeException을 발생시킵니다") {
          shouldThrow<StateDecodeException> {
            WorkflowStateJsonCodec.decode("{\"storeRevision\":\"not-a-number\"}")
          }
        }
      }
    })

private fun withRootField(encoded: String, normalizedName: String, value: JsonPrimitive): String {
  val root = WorkflowJson.format.parseToJsonElement(encoded).jsonObject
  val name = root.keys.single { it.replace("_", "").equals(normalizedName, ignoreCase = true) }
  return JsonObject(root + (name to value)).toString()
}

private fun fixture(): WorkflowState {
  val subTask =
      SubTask(
          id = "sk-101",
          taskId = "TASK-1",
          title = "Persist state",
          state = SubTaskState.APPROVED,
          workspace =
              Workspace("ws-1", "sk-101", WorkspacePath("/tmp/sk-101"), "sk-101", dirty = true),
          risk = Risk.HIGH,
          exposure = Exposure.FEATURE_FLAG,
          featureFlagId = "flag-1",
      )
  val human = Actor("alice", ActorKind.HUMAN, "Alice")
  val comment = ReviewComment("comment-1", human, "Please keep the state stable", 11, "state.kt", 4)
  val review =
      ReviewRevision(
          "rv-1",
          1,
          "State persistence review",
          listOf(
              ReviewThread("thread-1", ReviewLevel.R, listOf(comment), ThreadState.RESOLVED, true)
          ),
          10,
      )
  val diff = Diff("diff-1", listOf("state.kt"), additions = 12, deletions = 2)
  val change = ChangeRevision("cr-1", 1, diff, 10, providerRevision = "head-1")
  val pullRequest =
      PullRequest(
          "pr-1",
          "sk-101",
          "Persist workflow state",
          "Stores all workflow state in a deterministic JSON document.",
          "develop",
          PullRequestState.APPROVED,
          Risk.HIGH,
          Exposure.FEATURE_FLAG,
          "flag-1",
          review,
          change,
          Approval("approval-1", human, "cr-1", "diff-1", 12),
          CiStatus.PASSED,
      )
  val validation = Validation("build", "Build", ValidationStatus.PASSED, revision = "cr-1")
  val check = CheckResult("test", "Tests", ValidationStatus.PASSED, "fp-1", "cr-1")
  val queue =
      MergeQueueEntry(
          "mq-1",
          "sk-101",
          "pr-1",
          "cr-1",
          MergeQueueState.PASSED,
          listOf(validation),
      )
  val candidate =
      DeploymentCandidate(
          "candidate-1",
          "main-1",
          listOf("sk-101"),
          mapOf("sk-101" to Risk.HIGH),
          listOf(validation),
          DeploymentCandidateState.CANDIDATE,
      )
  val event = GateRecorded("sk-101", GateType.APPROVE, human, 20)
  return WorkflowState(
      storeRevision = 7,
      sequence =
          IdSequence(
              subTask = 101,
              review = 1,
              change = 1,
              thread = 1,
              comment = 1,
              approval = 1,
              mergeQueue = 1,
              candidate = 1,
              release = 1,
              audit = 1,
          ),
      tasks =
          mapOf(
              "task-1" to
                  Task(
                      "task-1",
                      ExternalTaskId("TASK-1"),
                      "Persistence",
                      TaskState.IN_PROGRESS,
                      listOf("sk-101"),
                  )
          ),
      subTasks = mapOf("sk-101" to subTask),
      pullRequests = mapOf("pr-1" to pullRequest),
      checks = mapOf("sk-101" to CheckSummary("fp-1", "cr-1", listOf(check))),
      integrations = mapOf("sk-101" to Integration("sk-101", IntegrationState.QUEUED, queue)),
      mergeQueue = mapOf("mq-1" to queue),
      deploymentCandidates = mapOf("candidate-1" to candidate),
      releases =
          mapOf(
              "release-1" to
                  Release("release-1", "candidate-1", "flag-1", ReleaseState.INTERNAL_VALIDATION)
          ),
      startRequests =
          mapOf(
              StartRequestKey("TASK-1", "req-1") to
                  StartRequestRecord(
                      StartRequestKey("TASK-1", "req-1"),
                      "sk-101",
                      "Persist state",
                  )
          ),
      syncConflicts = mapOf("sk-101" to SyncConflict("sk-101", subTask, listOf("state.kt"), 21)),
      eventLog =
          EventLog(
              listOf(event),
              listOf(
                  AuditEntry(
                      "audit-1",
                      human,
                      "approve",
                      "sk-101",
                      changeRevisionId = "cr-1",
                      occurredAtEpochMillis = 20,
                  )
              ),
          ),
  )
}
