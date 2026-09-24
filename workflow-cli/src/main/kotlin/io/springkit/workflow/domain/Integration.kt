package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

@Serializable
enum class MergeQueueState {
  NOT_QUEUED,
  QUEUED,
  VALIDATING,
  PASSED,
  FAILED,
  MERGED,
}

@Serializable
enum class IntegrationState {
  NOT_MERGED,
  QUEUED,
  VALIDATING,
  MERGED,
}

@Serializable
data class MergeQueueEntry(
    val id: String,
    val subTaskId: SubTaskId,
    val pullRequestId: PullRequestId,
    val changeRevisionId: ChangeRevisionId,
    val state: MergeQueueState = MergeQueueState.QUEUED,
    val validations: List<Validation> = emptyList(),
    /** Queue 등록 시점의 provider head revision입니다. */
    val providerRevision: String? = null,
) {
  init {
    require(id.isNotBlank()) { "merge queue id must not be blank" }
    require(subTaskId.isNotBlank()) { "merge queue subtask id must not be blank" }
    require(pullRequestId.isNotBlank()) { "merge queue pull request id must not be blank" }
    require(changeRevisionId.isNotBlank()) { "merge queue change revision must not be blank" }
    require(providerRevision == null || providerRevision.isNotBlank()) {
      "merge queue provider revision must not be blank"
    }
  }
}

@Serializable
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
