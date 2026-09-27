package io.springkit.workflow.adapter.store

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreEventRequest
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreTransactionState
import io.springkit.workflow.application.StoreWriteRequest
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.EventLog
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
import java.io.IOException
import java.nio.file.Files
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem

class OkioWorkflowStoreAdapterTest :
    FunSpec({
      context("저장소가 비어 있는 상태에서 스냅샷을 조회하면") {
        test("저장소가 비어 있으면, 초기 revision과 빈 컬렉션을 반환합니다") {
          val result =
              adapter().snapshot(StoreSnapshotRequest()).shouldBeTypeOf<PortResult.Success<*>>()
          val snapshot =
              result.value
                  .shouldBeTypeOf<io.springkit.workflow.application.StoreSnapshotResponse>()
                  .snapshot

          snapshot.revision shouldBe "0"
          snapshot.sequence shouldBe IdSequence()
          snapshot.tasks shouldBe emptyList()
          snapshot.subTasks shouldBe emptyList()
          snapshot.eventLog.events shouldBe emptyList()
        }
      }

      context("트랜잭션을 기록하고 커밋하면") {
        test("트랜잭션을 기록하고 커밋하면, 모든 workflow state 필드를 저장한 뒤 revision을 증가시켜 다시 읽습니다") {
          val fileSystem = FakeFileSystem()
          val path = "/workflow/state.json".toPath()
          val adapter = OkioWorkflowStoreAdapter(fileSystem, path)
          val expected = fixtureSnapshot("0")
          val transaction =
              StoreTransactionRequest(
                  "tx-1",
                  expectedRevision = "0",
                  idempotencyKey = "request-1",
              )

          adapter.begin(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          adapter
              .write(StoreWriteRequest(transaction.transactionId, "0", expected))
              .shouldBeTypeOf<PortResult.Success<*>>()
          val committed = adapter.commit(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          val commitResponse =
              committed.value.shouldBeTypeOf<
                  io.springkit.workflow.application.StoreTransactionResponse
              >()
          commitResponse.state shouldBe StoreTransactionState.COMMITTED
          commitResponse.revision shouldBe "1"

          val reopened = OkioWorkflowStoreAdapter(fileSystem, path).snapshot(StoreSnapshotRequest())
          val actual = reopened.shouldBeTypeOf<PortResult.Success<*>>().value
          actual
              .shouldBeTypeOf<io.springkit.workflow.application.StoreSnapshotResponse>()
              .snapshot shouldBe expected.copy(revision = "1")
        }
      }

      context("트랜잭션을 롤백하면") {
        test("트랜잭션을 롤백하면, 대상 상태를 기록하지 않고 현재 revision을 유지합니다") {
          val fileSystem = FakeFileSystem()
          val adapter = OkioWorkflowStoreAdapter(fileSystem, "/workflow/state.json".toPath())
          val transaction = StoreTransactionRequest("tx-rollback")

          adapter.begin(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          adapter
              .write(StoreWriteRequest(transaction.transactionId, "0", fixtureSnapshot("0")))
              .shouldBeTypeOf<PortResult.Success<*>>()
          val rollback = adapter.rollback(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          rollback.value
              .shouldBeTypeOf<io.springkit.workflow.application.StoreTransactionResponse>()
              .state shouldBe StoreTransactionState.ROLLED_BACK

          val result =
              adapter.snapshot(StoreSnapshotRequest()).shouldBeTypeOf<PortResult.Success<*>>()
          val snapshot =
              result.value
                  .shouldBeTypeOf<io.springkit.workflow.application.StoreSnapshotResponse>()
                  .snapshot
          snapshot.revision shouldBe "0"
          snapshot.tasks shouldBe emptyList()
        }
      }

      context("현재 revision과 다른 예상 revision으로 트랜잭션을 시작하면") {
        test("현재 revision과 다른 예상 revision으로 트랜잭션을 시작하면, 재시도 가능한 STALE_REVISION 오류를 반환합니다") {
          val result = adapter().begin(StoreTransactionRequest("tx-stale", expectedRevision = "7"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          failure.error.retryable shouldBe true
        }
      }

      context("같은 트랜잭션 식별자를 다른 idempotency 키와 함께 재사용하면") {
        test("같은 트랜잭션 식별자를 다른 idempotency 키와 함께 재사용하면, IDEMPOTENCY_CONFLICT 오류를 반환합니다") {
          val adapter = adapter()
          val first = StoreTransactionRequest("tx-identity", idempotencyKey = "same")

          adapter.begin(first).shouldBeTypeOf<PortResult.Success<*>>()
          adapter.begin(first).shouldBeTypeOf<PortResult.Success<*>>()
          val conflict = adapter.begin(first.copy(idempotencyKey = "different"))
          conflict.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "IDEMPOTENCY_CONFLICT"
          adapter.commit(first).shouldBeTypeOf<PortResult.Success<*>>()
          adapter.begin(first).shouldBeTypeOf<PortResult.Success<*>>()
          adapter
              .begin(first.copy(idempotencyKey = "different"))
              .shouldBeTypeOf<PortResult.Failure>()
              .error
              .code shouldBe "IDEMPOTENCY_CONFLICT"
        }
      }

      context("이벤트를 기록한 트랜잭션을 커밋하면") {
        test("이벤트를 기록한 트랜잭션을 커밋하면, 이벤트를 스냅샷에 추가합니다") {
          val fileSystem = FakeFileSystem()
          val adapter = OkioWorkflowStoreAdapter(fileSystem, "/workflow/state.json".toPath())
          val transaction = StoreTransactionRequest("tx-event")
          val event = GateRecorded("sk-101", GateType.READY, Actor("agent", ActorKind.AGENT), 10)

          adapter.begin(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          val appended = adapter.append(StoreEventRequest(transaction.transactionId, event))
          appended
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.StoreEventResponse>()
              .revision shouldBe "1"
          adapter.commit(transaction).shouldBeTypeOf<PortResult.Success<*>>()
          val snapshot =
              adapter
                  .snapshot(StoreSnapshotRequest())
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.StoreSnapshotResponse>()
                  .snapshot
          snapshot.eventLog.events shouldBe listOf(event)
        }
      }

      context("저장된 JSON이 올바르지 않은 상태에서 스냅샷을 조회하면") {
        test("저장된 JSON이 올바르지 않은 상태에서 스냅샷을 조회하면, STORE_CORRUPT 오류를 반환합니다") {
          val fileSystem = FakeFileSystem()
          val path = "/workflow/state.json".toPath()
          fileSystem.createDirectories(checkNotNull(path.parent))
          fileSystem.sink(path).buffer().use { it.writeUtf8("{not-json") }

          val failure =
              OkioWorkflowStoreAdapter(fileSystem, path)
                  .snapshot(StoreSnapshotRequest())
                  .shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STORE_CORRUPT"
        }
      }

      context("원자적 파일 이동이 실패하면") {
        test("원자적 파일 이동이 실패하면, 기존 저장 상태를 보존하고 STORE_IO_FAILURE 오류를 반환합니다") {
          val fileSystem = FakeFileSystem()
          val path = "/workflow/state.json".toPath()
          val initial = OkioWorkflowStoreAdapter(fileSystem, path)
          val first = StoreTransactionRequest("tx-first")
          initial.begin(first).shouldBeTypeOf<PortResult.Success<*>>()
          initial
              .write(StoreWriteRequest("tx-first", "0", fixtureSnapshot("0")))
              .shouldBeTypeOf<PortResult.Success<*>>()
          initial.commit(first).shouldBeTypeOf<PortResult.Success<*>>()
          val before = fileSystem.source(path).buffer().use { it.readUtf8() }

          val failing = OkioWorkflowStoreAdapter(FailingAtomicMoveFileSystem(fileSystem), path)
          val second = StoreTransactionRequest("tx-second", expectedRevision = "1")
          failing.begin(second).shouldBeTypeOf<PortResult.Success<*>>()
          failing
              .write(StoreWriteRequest("tx-second", "1", fixtureSnapshot("1")))
              .shouldBeTypeOf<PortResult.Success<*>>()
          val failure = failing.commit(second).shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "STORE_IO_FAILURE"
          fileSystem.source(path).buffer().use { it.readUtf8() } shouldBe before
        }
      }

      context("여러 프로세스가 잠금을 동시에 요청하면") {
        test("여러 프로세스가 잠금을 동시에 요청하면, 잠금을 먼저 획득한 트랜잭션만 진행하고 해제 후 다른 트랜잭션을 허용합니다") {
          val directory = Files.createTempDirectory("springkit-store-test")
          val path = directory.resolve("state.json").toOkioPath()
          try {
            val first = OkioWorkflowStoreAdapter(FileSystem.SYSTEM, path)
            val second = OkioWorkflowStoreAdapter(FileSystem.SYSTEM, path)
            val transaction = StoreTransactionRequest("tx-lock", expectedRevision = "0")

            first.begin(transaction).shouldBeTypeOf<PortResult.Success<*>>()
            val blocked = second.begin(StoreTransactionRequest("tx-other", expectedRevision = "0"))
            blocked.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "LOCK_UNAVAILABLE"
            first.rollback(transaction).shouldBeTypeOf<PortResult.Success<*>>()
            second
                .begin(StoreTransactionRequest("tx-other", expectedRevision = "0"))
                .shouldBeTypeOf<PortResult.Success<*>>()
            second
                .rollback(StoreTransactionRequest("tx-other", expectedRevision = "0"))
                .shouldBeTypeOf<PortResult.Success<*>>()
          } finally {
            FileSystem.SYSTEM.deleteRecursively(directory.toOkioPath(), mustExist = false)
          }
        }
      }
    })

private fun adapter(): OkioWorkflowStoreAdapter =
    OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/state.json".toPath())

private fun fixtureSnapshot(revision: String): WorkflowStoreSnapshot {
  val state = fixtureState().copy(storeRevision = revision.toLong())
  val subTasks = state.subTasks.values.sortedBy { it.id }
  return WorkflowStoreSnapshot(
      revision = revision,
      sequence = state.sequence,
      tasks = state.tasks.values.toList(),
      subTasks = subTasks,
      dependencies =
          subTasks.mapNotNull { it.requires?.let { required -> Dependency(it.id, required) } },
      workspaces = subTasks.mapNotNull { it.workspace },
      pullRequests = state.pullRequests.values.toList(),
      checks = state.checks,
      integrations = state.integrations.values.toList(),
      mergeQueue = state.mergeQueue.values.toList(),
      candidates = state.deploymentCandidates.values.toList(),
      releases = state.releases.values.toList(),
      startRequests = state.startRequests,
      syncConflicts = state.syncConflicts,
      eventLog = state.eventLog,
  )
}

private fun fixtureState(): WorkflowState {
  val human = Actor("alice", ActorKind.HUMAN, "Alice")
  val validation = Validation("build", "Build", ValidationStatus.PASSED, revision = "cr-1")
  val workspaceA = Workspace("ws-a", "sk-101", WorkspacePath("/tmp/sk-101"), "sk-101", dirty = true)
  val workspaceB =
      Workspace("ws-b", "sk-102", WorkspacePath("/tmp/sk-102"), "sk-102", managed = false)
  val task =
      Task(
          "task-1",
          io.springkit.workflow.domain.ExternalTaskId("external-1"),
          "Task",
          TaskState.IN_PROGRESS,
          listOf("sk-101", "sk-102"),
      )
  val subTaskA =
      SubTask(
          "sk-101",
          "task-1",
          "First",
          SubTaskState.APPROVED,
          workspace = workspaceA,
          risk = Risk.HIGH,
      )
  val subTaskB = SubTask("sk-102", "task-1", "Second", requires = "sk-101", workspace = workspaceB)
  val comment = ReviewComment("comment-1", human, "Looks good", 10, "src/App.kt", 4)
  val review =
      ReviewRevision(
          "rv-1",
          1,
          "Review",
          listOf(
              ReviewThread("thread-1", ReviewLevel.R, listOf(comment), ThreadState.RESOLVED, true)
          ),
          10,
      )
  val change = ChangeRevision("cr-1", 1, Diff("diff-1", listOf("src/App.kt"), 3, 1), 11)
  val pullRequest =
      PullRequest(
          "pr-1",
          "sk-101",
          "First",
          "Body",
          "develop",
          PullRequestState.APPROVED,
          Risk.HIGH,
          review,
          change,
          Approval("approval-1", human, "cr-1", "diff-1", 12),
          CiStatus.PASSED,
      )
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
          listOf("sk-101", "sk-102"),
          mapOf("sk-101" to Risk.HIGH),
          listOf(validation),
      )
  val event = GateRecorded("sk-101", GateType.APPROVE, human, 20)
  return WorkflowState(
      storeRevision = 0,
      sequence = IdSequence(102, 1, 1, 1, 1, 1, 1, 1, 1, 1),
      tasks = mapOf(task.id to task),
      subTasks = mapOf(subTaskA.id to subTaskA, subTaskB.id to subTaskB),
      pullRequests = mapOf(pullRequest.id to pullRequest),
      checks =
          mapOf(
              "sk-101" to
                  CheckSummary(
                      "fp-1",
                      "cr-1",
                      listOf(CheckResult("test", "Tests", ValidationStatus.PASSED, "fp-1", "cr-1")),
                  )
          ),
      integrations =
          mapOf(
              "sk-101" to
                  Integration("sk-101", IntegrationState.MERGED, queue, "main-1", "squash-1")
          ),
      mergeQueue = mapOf(queue.id to queue),
      deploymentCandidates = mapOf(candidate.id to candidate),
      releases =
          mapOf(
              "release-1" to Release("release-1", "candidate-1", ReleaseState.RELEASED, true, true)
          ),
      startRequests =
          mapOf(
              StartRequestKey("task-1", "request-1") to
                  StartRequestRecord(
                      StartRequestKey("task-1", "request-1"),
                      "sk-102",
                      "Second",
                      "sk-101",
                  )
          ),
      syncConflicts = mapOf("sk-102" to SyncConflict("sk-102", subTaskB, listOf("src/App.kt"), 21)),
      eventLog =
          EventLog(
              listOf(event),
              listOf(AuditEntry("audit-1", human, "approve", "sk-101", occurredAtEpochMillis = 20)),
          ),
  )
}

private class FailingAtomicMoveFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
  override fun atomicMove(source: Path, target: Path) {
    throw IOException("injected atomic move failure")
  }
}
