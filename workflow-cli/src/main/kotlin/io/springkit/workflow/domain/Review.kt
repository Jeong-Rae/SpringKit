package io.springkit.workflow.domain

enum class Risk {
  NORMAL,
  HIGH,
}

enum class Exposure {
  UNCHANGED,
  FEATURE_FLAG,
}

enum class PullRequestState {
  DRAFT,
  READY,
  REVIEW,
  APPROVED,
  BLOCKED,
  QUEUED,
  MERGED,
  CLOSED,
}

enum class ReviewLevel {
  R,
  C,
  A,
}

enum class ThreadState {
  OPEN,
  RESOLVED,
}

enum class ActorKind {
  HUMAN,
  AGENT,
  WORKFLOW,
}

data class Actor(
    val id: String,
    val kind: ActorKind,
    val name: String? = null,
) {
  init {
    require(id.isNotBlank()) { "actor id must not be blank" }
  }

  val isHuman: Boolean
    get() = kind == ActorKind.HUMAN
}

data class ReviewComment(
    val id: String,
    val author: Actor,
    val body: String,
    val createdAtEpochMillis: Long = 0,
    val path: String? = null,
    val line: Int? = null,
) {
  init {
    require(id.isNotBlank()) { "comment id must not be blank" }
    require(body.isNotBlank()) { "comment body must not be blank" }
    require((path == null) == (line == null)) { "path and line must be supplied together" }
    require(line == null || line > 0) { "comment line must be positive" }
  }
}

data class ReviewThread(
    val id: ThreadId,
    val level: ReviewLevel,
    val comments: List<ReviewComment>,
    val state: ThreadState = ThreadState.OPEN,
    val requiresHumanResolution: Boolean = comments.any { it.author.isHuman },
) {
  init {
    require(id.isNotBlank()) { "thread id must not be blank" }
    require(comments.isNotEmpty()) { "thread must have at least one comment" }
    require(comments.map { it.id }.distinct().size == comments.size) {
      "thread comments must be unique"
    }
  }

  val isOpen: Boolean
    get() = state == ThreadState.OPEN

  val isBlocking: Boolean
    get() = isOpen && level == ReviewLevel.R

  fun reply(comment: ReviewComment): ReviewThread =
      copy(
          comments = comments + comment,
          requiresHumanResolution = requiresHumanResolution || comment.author.isHuman,
      )

  fun resolve(actor: Actor): ReviewThread {
    require(!requiresHumanResolution || actor.isHuman) {
      "human review thread requires a human resolver"
    }
    return copy(state = ThreadState.RESOLVED)
  }
}

data class Diff(
    val identity: DiffIdentity,
    val files: List<String> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
) {
  init {
    require(identity.isNotBlank()) { "diff identity must not be blank" }
    require(additions >= 0) { "diff additions must not be negative" }
    require(deletions >= 0) { "diff deletions must not be negative" }
  }
}

data class ReviewRevision(
    val id: ReviewRevisionId,
    val number: Long,
    val body: String,
    val threads: List<ReviewThread> = emptyList(),
    val createdAtEpochMillis: Long = 0,
) {
  init {
    require(id.isNotBlank()) { "review revision id must not be blank" }
    require(number > 0) { "review revision number must be positive" }
    require(body.isNotBlank()) { "review body must not be blank" }
    require(threads.map { it.id }.distinct().size == threads.size) {
      "review thread ids must be unique"
    }
  }

  val openThreads: List<ReviewThread>
    get() = threads.filter { it.isOpen }

  val openRequiredThreads: List<ReviewThread>
    get() = openThreads.filter { it.level == ReviewLevel.R }
}

data class ChangeRevision(
    val id: ChangeRevisionId,
    val number: Long,
    val diff: Diff,
    val createdAtEpochMillis: Long = 0,
) {
  init {
    require(id.isNotBlank()) { "change revision id must not be blank" }
    require(number > 0) { "change revision number must be positive" }
  }
}

data class PullRequest(
    val id: PullRequestId,
    val subTaskId: SubTaskId,
    val title: String,
    val body: String,
    val base: BranchName,
    val state: PullRequestState = PullRequestState.DRAFT,
    val risk: Risk = Risk.NORMAL,
    val exposure: Exposure = Exposure.UNCHANGED,
    val featureFlagId: FeatureFlagId? = null,
    val reviewRevision: ReviewRevision,
    val changeRevision: ChangeRevision,
    val approval: Approval? = null,
    val ci: CiStatus = CiStatus.PENDING,
    val aiReview: AiReviewStatus = AiReviewStatus.PENDING,
) {
  init {
    require(id.isNotBlank()) { "pull request id must not be blank" }
    require(subTaskId.isNotBlank()) { "pull request subtask id must not be blank" }
    require(title.isNotBlank()) { "pull request title must not be blank" }
    require(body.isNotBlank()) { "pull request body must not be blank" }
    require(base.isNotBlank()) { "pull request base must not be blank" }
    require(exposure != Exposure.FEATURE_FLAG || !featureFlagId.isNullOrBlank()) {
      "feature-flag exposure requires a feature flag id"
    }
  }
}

data class Approval(
    val id: String,
    val actor: Actor,
    val changeRevisionId: ChangeRevisionId,
    val diffIdentity: DiffIdentity,
    val approvedAtEpochMillis: Long = 0,
    val active: Boolean = true,
) {
  init {
    require(id.isNotBlank()) { "approval id must not be blank" }
    require(actor.isHuman) { "only a human can approve a change" }
    require(changeRevisionId.isNotBlank()) { "approval change revision must not be blank" }
    require(diffIdentity.isNotBlank()) { "approval diff identity must not be blank" }
  }
}

enum class CiStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
}

enum class AiReviewStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
}
