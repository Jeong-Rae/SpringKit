package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

/*
 * 도메인 식별자는 의도적으로 문자열로 표현합니다. 별칭을 사용해 저장소나 제공자 식별자 타입과 도메인을 분리하고 호출부에서 각 필드의 의미를 명확히 합니다.
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

typealias MainRevision = String

@Serializable
data class ExternalTaskId(val value: String) {
  init {
    require(value.isNotBlank()) { "external task id must not be blank" }
  }
}

@Serializable
data class WorkspacePath(val value: String) {
  init {
    require(value.isNotBlank()) { "workspace path must not be blank" }
  }
}
