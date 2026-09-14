package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

enum class FailureCode {
  UNKNOWN_COMMAND,
  INVALID_ARGUMENT,
  INVALID_TARGET_SELECTION,
  INVARIANT_VIOLATION,
  STATE_CONFLICT,
  STORE_FAILURE,
  EXTERNAL_FAILURE,
  STALE_REVISION,
  STALE_DIFF_IDENTITY,
  HUMAN_REQUIRED,
  HUMAN_APPROVAL_REQUIRED,
  HUMAN_REVIEW_REQUIRED,
  DEPENDENCY_CYCLE,
  DEPENDENCY_NOT_FOUND,
  DEPENDENCY_NOT_MERGED,
  WORKTREE_REQUIRED,
  WORKTREE_DIRTY,
  TASK_NOT_FOUND,
  SUBTASK_NOT_FOUND,
  WORKSPACE_NOT_FOUND,
  IDEMPOTENCY_CONFLICT,
  REVIEW_NOT_FOUND,
  REVIEW_ALREADY_OPEN,
  REVIEW_REVISION_MISSING,
  THREAD_NOT_FOUND,
  REQUIRED_THREAD_OPEN,
  INVALID_GATE_STATE,
  VALIDATION_REQUIRED,
  CHECK_NOT_PASSED,
  CI_NOT_PASSED,
  AI_REVIEW_FAILED,
  SYNC_CONFLICT,
  MERGE_QUEUE_FAILED,
  DEPLOYMENT_NOT_FOUND,
  DEPLOYMENT_GATE_BLOCKED,
  RELEASE_NOT_FOUND,
  RELEASE_GATE_BLOCKED,
}

data class BlockedBy(
    val code: String,
    val message: String,
    val target: String? = null,
)

data class NextAction(
    val actor: ActorKind,
    val action: String,
    val command: String? = null,
)

data class FailureData(
    val code: FailureCode,
    val message: String,
    val blockedBy: List<BlockedBy> = emptyList(),
    val next: List<NextAction> = emptyList(),
)

sealed interface WorkflowResult<out T> {
  data class Success<T>(
      val data: T,
      val next: List<NextAction> = emptyList(),
  ) : WorkflowResult<T>

  data class Failure(
      val data: FailureData,
  ) : WorkflowResult<Nothing>
}

@Serializable
sealed interface DomainEvent {
  val targetId: String
  val occurredAtEpochMillis: Long
}

@Serializable
data class SubTaskStarted(
    override val targetId: SubTaskId,
    val taskId: TaskId,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class ReviewOpened(
    override val targetId: PullRequestId,
    val subTaskId: SubTaskId,
    val reviewRevisionId: ReviewRevisionId,
    val changeRevisionId: ChangeRevisionId,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class ReviewChanged(
    override val targetId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val changeRevisionId: ChangeRevisionId?,
    val diffChanged: Boolean,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class GateRecorded(
    override val targetId: String,
    val gate: GateType,
    val actor: Actor,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class MergeRecorded(
    override val targetId: SubTaskId,
    val mainRevision: MainRevision,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class DeploymentRecorded(
    override val targetId: CandidateId,
    val state: DeploymentCandidateState,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class ReleaseRecorded(
    override val targetId: ReleaseId,
    val state: ReleaseState,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

@Serializable
data class AuditEntry(
    val id: String,
    val actor: Actor,
    val action: String,
    val targetId: String,
    val reviewRevisionId: ReviewRevisionId? = null,
    val changeRevisionId: ChangeRevisionId? = null,
    val mainRevision: MainRevision? = null,
    val occurredAtEpochMillis: Long = 0,
) {
  init {
    require(id.isNotBlank()) { "audit id must not be blank" }
    require(action.isNotBlank()) { "audit action must not be blank" }
    require(targetId.isNotBlank()) { "audit target id must not be blank" }
  }
}

@Serializable
data class WorkflowCounters(
    val startedSubTasks: Int = 0,
    val openedReviews: Int = 0,
    val changedReviews: Int = 0,
    val recordedGates: Int = 0,
    val mergedSubTasks: Int = 0,
    val deploymentCandidates: Int = 0,
    val releases: Int = 0,
) {
  init {
    require(
        listOf(
                startedSubTasks,
                openedReviews,
                changedReviews,
                recordedGates,
                mergedSubTasks,
                deploymentCandidates,
                releases,
            )
            .all { it >= 0 }
    ) {
      "workflow counters must not be negative"
    }
  }

  fun record(event: DomainEvent): WorkflowCounters =
      when (event) {
        is SubTaskStarted -> copy(startedSubTasks = startedSubTasks + 1)
        is ReviewOpened -> copy(openedReviews = openedReviews + 1)
        is ReviewChanged -> copy(changedReviews = changedReviews + 1)
        is GateRecorded -> copy(recordedGates = recordedGates + 1)
        is MergeRecorded -> copy(mergedSubTasks = mergedSubTasks + 1)
        is DeploymentRecorded -> copy(deploymentCandidates = deploymentCandidates + 1)
        is ReleaseRecorded -> copy(releases = releases + 1)
      }
}

@Serializable
data class EventLog(
    val events: List<DomainEvent> = emptyList(),
    val audits: List<AuditEntry> = emptyList(),
    val counters: WorkflowCounters = WorkflowCounters(),
) {
  fun append(event: DomainEvent, audit: AuditEntry? = null): EventLog =
      copy(
          events = events + event,
          audits = if (audit == null) audits else audits + audit,
          counters = counters.record(event),
      )
}

@Serializable
enum class GateType {
  READY,
  APPROVE,
  DEPLOY,
  RELEASE,
}
