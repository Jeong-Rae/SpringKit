package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

@Serializable
data class StartRequestKey(
    val taskId: TaskId,
    val requestId: String,
) {
  init {
    require(taskId.isNotBlank()) { "start request task id must not be blank" }
    require(requestId.isNotBlank()) { "start request id must not be blank" }
  }
}

@Serializable
data class StartRequestRecord(
    val key: StartRequestKey,
    val subTaskId: SubTaskId,
    val title: String,
    val requires: SubTaskId? = null,
) {
  init {
    require(subTaskId.isNotBlank()) { "start request subtask id must not be blank" }
    require(title.isNotBlank()) { "start request title must not be blank" }
  }
}

@Serializable
data class SyncConflict(
    val subTaskId: SubTaskId,
    val before: SubTask,
    val conflictPaths: List<String>,
    val startedAtEpochMillis: Long,
) {
  init {
    require(subTaskId == before.id) { "sync snapshot must belong to the target subtask" }
    require(conflictPaths.isNotEmpty()) { "sync conflict must identify at least one path" }
    require(conflictPaths.all { it.isNotBlank() }) { "sync conflict paths must not be blank" }
    require(conflictPaths.distinct().size == conflictPaths.size) {
      "sync conflict paths must be unique"
    }
  }
}

@Serializable
data class IdSequence(
    val subTask: Long = 100,
    val review: Long = 0,
    val change: Long = 0,
    val thread: Long = 0,
    val comment: Long = 0,
    val approval: Long = 0,
    val mergeQueue: Long = 0,
    val candidate: Long = 0,
    val release: Long = 0,
    val audit: Long = 0,
) {
  init {
    require(
        listOf(
                subTask,
                review,
                change,
                thread,
                comment,
                approval,
                mergeQueue,
                candidate,
                release,
                audit,
            )
            .all { it >= 0 },
    ) {
      "id sequence values must not be negative"
    }
  }
}

@Serializable
data class WorkflowState(
    val schemaVersion: Int = 1,
    val storeRevision: Long = 0,
    val sequence: IdSequence = IdSequence(),
    val tasks: Map<TaskId, Task> = emptyMap(),
    val subTasks: Map<SubTaskId, SubTask> = emptyMap(),
    val pullRequests: Map<PullRequestId, PullRequest> = emptyMap(),
    val checks: Map<SubTaskId, CheckSummary> = emptyMap(),
    val integrations: Map<SubTaskId, Integration> = emptyMap(),
    val mergeQueue: Map<String, MergeQueueEntry> = emptyMap(),
    val deploymentCandidates: Map<CandidateId, DeploymentCandidate> = emptyMap(),
    val releases: Map<ReleaseId, Release> = emptyMap(),
    val startRequests: Map<StartRequestKey, StartRequestRecord> = emptyMap(),
    val syncConflicts: Map<SubTaskId, SyncConflict> = emptyMap(),
    val eventLog: EventLog = EventLog(),
) {
  init {
    require(schemaVersion == 1) { "unsupported workflow state schema version: $schemaVersion" }
    require(storeRevision >= 0) { "store revision must not be negative" }
    require(tasks.all { (id, task) -> id == task.id }) { "task map keys must match task ids" }
    require(subTasks.all { (id, subTask) -> id == subTask.id }) {
      "subtask map keys must match subtask ids"
    }
    require(subTasks.values.all { it.requires == null || it.requires in subTasks }) {
      "subtask dependencies must reference known subtasks"
    }
    require(pullRequests.all { (id, pullRequest) -> id == pullRequest.id }) {
      "pull request map keys must match pull request ids"
    }
    require(pullRequests.values.all { it.subTaskId in subTasks }) {
      "pull requests must reference known subtasks"
    }
    require(checks.keys.all { it in subTasks }) { "checks must reference known subtasks" }
    require(
        integrations.all { (id, integration) -> id == integration.subTaskId && id in subTasks }
    ) {
      "integrations must reference known subtasks"
    }
    require(mergeQueue.all { (id, entry) -> id == entry.id && entry.subTaskId in subTasks }) {
      "merge queue entries must reference known subtasks"
    }
    require(deploymentCandidates.all { (id, candidate) -> id == candidate.id }) {
      "deployment candidate map keys must match candidate ids"
    }
    require(
        deploymentCandidates.values.all { candidate ->
          candidate.includedSubTasks.all { it in subTasks }
        },
    ) {
      "deployment candidates must reference known subtasks"
    }
    require(releases.all { (id, release) -> id == release.id }) {
      "release map keys must match release ids"
    }
    require(releases.values.all { it.candidateId in deploymentCandidates }) {
      "releases must reference known deployment candidates"
    }
    require(
        startRequests.all { (key, record) -> key == record.key && record.subTaskId in subTasks }
    ) {
      "start requests must reference known subtasks"
    }
    require(syncConflicts.all { (id, conflict) -> id == conflict.subTaskId && id in subTasks }) {
      "sync conflicts must reference known subtasks"
    }
    require(
        !hasDependencyCycle(
            subTasks.values.mapNotNull { subTask ->
              subTask.requires?.let { Dependency(subTask.id, it) }
            },
        ),
    ) {
      "workflow state must not contain a dependency cycle"
    }
  }

  fun nextSubTaskId(project: String): Pair<WorkflowState, SubTaskId> {
    require(project.matches(Regex("[A-Za-z][A-Za-z0-9._-]*"))) {
      "project prefix must be safe for a subtask id"
    }
    val next = sequence.subTask + 1
    return copy(sequence = sequence.copy(subTask = next)) to "$project-$next"
  }

  fun nextReviewRevision(): Pair<WorkflowState, ReviewRevisionId> {
    val next = sequence.review + 1
    return copy(sequence = sequence.copy(review = next)) to "rv-$next"
  }

  fun nextChangeRevision(): Pair<WorkflowState, ChangeRevisionId> {
    val next = sequence.change + 1
    return copy(sequence = sequence.copy(change = next)) to "cr-$next"
  }

  fun withSubTask(subTask: SubTask): WorkflowState =
      copy(subTasks = subTasks + (subTask.id to subTask))

  fun withPullRequest(pullRequest: PullRequest): WorkflowState =
      copy(
          pullRequests = pullRequests + (pullRequest.id to pullRequest),
          subTasks =
              subTasks[pullRequest.subTaskId]?.let { current ->
                subTasks +
                    (current.id to
                        current.copy(
                            pullRequestId = pullRequest.id,
                            state = pullRequest.state.toSubTaskState(),
                        ))
              } ?: subTasks,
      )
}

fun PullRequestState.toSubTaskState(): SubTaskState =
    when (this) {
      PullRequestState.DRAFT -> SubTaskState.DRAFT
      PullRequestState.READY -> SubTaskState.READY
      PullRequestState.REVIEW -> SubTaskState.REVIEW
      PullRequestState.APPROVED -> SubTaskState.APPROVED
      PullRequestState.BLOCKED -> SubTaskState.BLOCKED
      PullRequestState.QUEUED -> SubTaskState.QUEUED
      PullRequestState.MERGED -> SubTaskState.MERGED
      PullRequestState.CLOSED -> SubTaskState.BLOCKED
    }
