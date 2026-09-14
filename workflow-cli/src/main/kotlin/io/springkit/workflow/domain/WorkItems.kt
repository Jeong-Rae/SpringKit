package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SubTaskState {
  DEVELOPMENT,
  DRAFT,
  READY,
  REVIEW,
  APPROVED,
  BLOCKED,
  QUEUED,
  MERGED,
}

@Serializable
enum class TaskState {
  OPEN,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED,
}

@Serializable
data class Task(
    val id: TaskId,
    val externalId: ExternalTaskId,
    val title: String,
    val state: TaskState = TaskState.OPEN,
    val subTaskIds: List<SubTaskId> = emptyList(),
) {
  init {
    require(id.isNotBlank()) { "task id must not be blank" }
    require(title.isNotBlank()) { "task title must not be blank" }
    require(subTaskIds.distinct().size == subTaskIds.size) { "task subtask ids must be unique" }
  }
}

@Serializable
data class Workspace(
    val id: WorkspaceId,
    val subTaskId: SubTaskId,
    val path: WorkspacePath,
    val branch: BranchName,
    val managed: Boolean = true,
    val dirty: Boolean = false,
) {
  init {
    require(id.isNotBlank()) { "workspace id must not be blank" }
    require(subTaskId.isNotBlank()) { "workspace subtask id must not be blank" }
    require(branch.isNotBlank()) { "workspace branch must not be blank" }
  }
}

data class Dependency(
    val subTaskId: SubTaskId,
    val requires: SubTaskId,
) {
  init {
    require(subTaskId.isNotBlank()) { "dependency subtask id must not be blank" }
    require(requires.isNotBlank()) { "dependency target must not be blank" }
    require(subTaskId != requires) { "a subtask cannot require itself" }
  }
}

data class TaskGraph(
    val subTasks: List<SubTask> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
) {
  init {
    val ids = subTasks.map { it.id }
    require(ids.distinct().size == ids.size) { "task graph subtask ids must be unique" }
    require(dependencies.distinct().size == dependencies.size) {
      "task graph dependencies must be unique"
    }
    require(dependencies.groupingBy { it.subTaskId }.eachCount().values.all { it == 1 }) {
      "a subtask can have at most one direct dependency"
    }
    require(dependencies.all { it.subTaskId in ids && it.requires in ids }) {
      "task graph dependencies must reference known subtasks"
    }
    require(!hasDependencyCycle(dependencies)) { "task graph must not contain a dependency cycle" }
  }

  fun withDependency(dependency: Dependency): TaskGraph =
      copy(
          dependencies =
              dependencies.filterNot { it.subTaskId == dependency.subTaskId } + dependency
      )

  fun withoutDependency(subTaskId: SubTaskId): TaskGraph =
      copy(dependencies = dependencies.filterNot { it.subTaskId == subTaskId })
}

@Serializable
data class SubTask(
    val id: SubTaskId,
    val taskId: TaskId,
    val title: String,
    val state: SubTaskState = SubTaskState.DEVELOPMENT,
    val branch: BranchName = id,
    val workspace: Workspace? = null,
    val requires: SubTaskId? = null,
    val pullRequestId: PullRequestId? = null,
    val risk: Risk = Risk.NORMAL,
    val exposure: Exposure = Exposure.UNCHANGED,
    val featureFlagId: FeatureFlagId? = null,
) {
  init {
    require(id.isNotBlank()) { "subtask id must not be blank" }
    require(taskId.isNotBlank()) { "subtask task id must not be blank" }
    require(title.isNotBlank()) { "subtask title must not be blank" }
    require(branch == id) { "subtask branch must equal subtask id" }
    require(exposure != Exposure.FEATURE_FLAG || !featureFlagId.isNullOrBlank()) {
      "feature-flag exposure requires a feature flag id"
    }
    require(exposure != Exposure.UNCHANGED || featureFlagId == null) {
      "unchanged exposure must not have a feature flag id"
    }
  }
}

fun SubTask.withState(state: SubTaskState): SubTask = copy(state = state)

fun SubTask.withDependency(requires: SubTaskId?): SubTask = copy(requires = requires)
