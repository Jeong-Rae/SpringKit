package io.springkit.workflow.domain

enum class MergeQueueState {
  NOT_QUEUED,
  QUEUED,
  VALIDATING,
  PASSED,
  FAILED,
  MERGED,
}

enum class IntegrationState {
  NOT_MERGED,
  QUEUED,
  VALIDATING,
  MERGED,
}

data class MergeQueueEntry(
    val id: String,
    val subTaskId: SubTaskId,
    val pullRequestId: PullRequestId,
    val changeRevisionId: ChangeRevisionId,
    val state: MergeQueueState = MergeQueueState.QUEUED,
    val validations: List<Validation> = emptyList(),
) {
  init {
    require(id.isNotBlank()) { "merge queue id must not be blank" }
    require(subTaskId.isNotBlank()) { "merge queue subtask id must not be blank" }
    require(pullRequestId.isNotBlank()) { "merge queue pull request id must not be blank" }
    require(changeRevisionId.isNotBlank()) { "merge queue change revision must not be blank" }
  }
}

data class Integration(
    val subTaskId: SubTaskId,
    val state: IntegrationState = IntegrationState.NOT_MERGED,
    val mergeQueue: MergeQueueEntry? = null,
    val mainRevision: MainRevision? = null,
    val squashCommit: String? = null,
) {
  init {
    require(subTaskId.isNotBlank()) { "integration subtask id must not be blank" }
    require(state != IntegrationState.MERGED || !mainRevision.isNullOrBlank()) {
      "merged integration must have a main revision"
    }
  }
}

data class MergeQueueCondition(
    val ready: Boolean,
    val approvalApplicable: Boolean,
    val requiredCiPassed: Boolean,
    val requiredValidationsPassed: Boolean,
    val openRequiredThreads: Boolean,
    val dependencyIntegrated: Boolean,
) {
  val canQueue: Boolean
    get() =
        ready &&
            approvalApplicable &&
            requiredCiPassed &&
            requiredValidationsPassed &&
            !openRequiredThreads &&
            dependencyIntegrated
}
