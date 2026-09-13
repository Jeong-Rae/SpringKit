package io.springkit.workflow.domain

/**
 * Domain identifiers are deliberately represented by strings. The aliases keep the domain
 * independent from a persistence or provider identifier type while making the intent of each field
 * explicit at call sites.
 */
typealias TaskId = String

typealias SubTaskId = String

typealias WorkspaceId = String

typealias BranchName = String

typealias PullRequestId = String

typealias ReviewRevisionId = String

typealias ChangeRevisionId = String

typealias DiffIdentity = String

typealias ThreadId = String

typealias CheckId = String

typealias ValidationId = String

typealias CandidateId = String

typealias ReleaseId = String

typealias FeatureFlagId = String

typealias MainRevision = String

data class ExternalTaskId(val value: String) {
  init {
    require(value.isNotBlank()) { "external task id must not be blank" }
  }
}

data class WorkspacePath(val value: String) {
  init {
    require(value.isNotBlank()) { "workspace path must not be blank" }
  }
}
