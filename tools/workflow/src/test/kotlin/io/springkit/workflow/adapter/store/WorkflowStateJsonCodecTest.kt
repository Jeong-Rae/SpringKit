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
import io.springkit.workflow.domain.SubTaskCleanupState
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

      context("이전 Feature Flag 필드가 포함된 상태를 디코딩하면") {
        test("구형 필드를 무시하고 다시 인코딩할 때 제거합니다") {
          val state = fixture()
          val root =
              WorkflowJson.format
                  .parseToJsonElement(WorkflowStateJsonCodec.encodeToString(state))
                  .jsonObject
          fun withLegacyFields(collection: String, id: String, fields: Map<String, JsonPrimitive>) =
              JsonObject(
                  root.getValue(collection).jsonObject.let { records ->
                    records + (id to JsonObject(records.getValue(id).jsonObject + fields))
                  }
              )
          val legacyRoot =
              JsonObject(
                  root +
                      ("sub_tasks" to
                          withLegacyFields(
                              "sub_tasks",
                              "sk-101",
                              mapOf(
                                  "exposure" to JsonPrimitive("FEATURE_FLAG"),
                                  "feature_flag_id" to JsonPrimitive("flag-1"),
                              ),
                          )) +
                      ("pull_requests" to
                          withLegacyFields(
                              "pull_requests",
                              "pr-1",
                              mapOf(
                                  "exposure" to JsonPrimitive("FEATURE_FLAG"),
                                  "feature_flag_id" to JsonPrimitive("flag-1"),
                              ),
                          )) +
                      ("releases" to
                          withLegacyFields(
                              "releases",
                              "release-1",
                              mapOf("feature_flag_id" to JsonPrimitive("flag-1")),
                          ))
              )

          val decoded = WorkflowStateJsonCodec.decode(legacyRoot)
          val encoded =
              WorkflowJson.format
                  .parseToJsonElement(WorkflowStateJsonCodec.encodeToString(decoded))
                  .jsonObject

          decoded shouldBe state
          encoded
              .getValue("sub_tasks")
              .jsonObject
              .getValue("sk-101")
              .jsonObject["feature_flag_id"] shouldBe null
          encoded
              .getValue("pull_requests")
              .jsonObject
              .getValue("pr-1")
              .jsonObject["exposure"] shouldBe null
          encoded
              .getValue("releases")
              .jsonObject
              .getValue("release-1")
              .jsonObject["feature_flag_id"] shouldBe null
        }
      }

      context("Merge 이후 정리 SHA checkpoint를 JSON으로 저장하면") {
        test("다시 읽은 상태에 검증된 Git SHA가 보존됩니다") {
          val base = fixture()
          val subTask =
              base.subTasks
                  .getValue("sk-101")
                  .copy(
                      state = SubTaskState.MERGED,
                      cleanupState = SubTaskCleanupState.REMOTE_BRANCH_REMOVED,
                      cleanupRevision = "0123456789abcdef0123456789abcdef01234567",
                  )
          val state = base.copy(subTasks = base.subTasks + (subTask.id to subTask))

          val decoded = WorkflowStateJsonCodec.decode(WorkflowStateJsonCodec.encodeToString(state))

          decoded.subTasks.getValue(subTask.id).cleanupRevision shouldBe subTask.cleanupRevision
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
              "release-1" to Release("release-1", "candidate-1", ReleaseState.INTERNAL_VALIDATION)
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
