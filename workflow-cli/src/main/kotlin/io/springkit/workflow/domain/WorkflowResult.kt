package io.springkit.workflow.domain

enum class FailureCode {
  INVALID_ARGUMENT,
  INVARIANT_VIOLATION,
  STATE_CONFLICT,
  STALE_REVISION,
  HUMAN_REQUIRED,
  DEPENDENCY_CYCLE,
  WORKTREE_REQUIRED,
  VALIDATION_REQUIRED,
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

sealed interface DomainEvent {
  val targetId: String
  val occurredAtEpochMillis: Long
}

data class SubTaskStarted(
    override val targetId: SubTaskId,
    val taskId: TaskId,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class ReviewOpened(
    override val targetId: PullRequestId,
    val subTaskId: SubTaskId,
    val reviewRevisionId: ReviewRevisionId,
    val changeRevisionId: ChangeRevisionId,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class ReviewChanged(
    override val targetId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val changeRevisionId: ChangeRevisionId?,
    val diffChanged: Boolean,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class GateRecorded(
    override val targetId: String,
    val gate: GateType,
    val actor: Actor,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class MergeRecorded(
    override val targetId: SubTaskId,
    val mainRevision: MainRevision,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class DeploymentRecorded(
    override val targetId: CandidateId,
    val state: DeploymentCandidateState,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

data class ReleaseRecorded(
    override val targetId: ReleaseId,
    val state: ReleaseState,
    override val occurredAtEpochMillis: Long = 0,
) : DomainEvent

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

enum class GateType {
  READY,
  APPROVE,
  DEPLOY,
  RELEASE,
}
