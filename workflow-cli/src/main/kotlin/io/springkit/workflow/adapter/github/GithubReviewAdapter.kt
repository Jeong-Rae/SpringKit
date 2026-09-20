package io.springkit.workflow.adapter.github

import io.springkit.workflow.application.AddReviewCommentRequest
import io.springkit.workflow.application.AddReviewCommentResponse
import io.springkit.workflow.application.ApproveReviewRequest
import io.springkit.workflow.application.ApproveReviewResponse
import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.GetReviewRequest
import io.springkit.workflow.application.GetReviewResponse
import io.springkit.workflow.application.OpenReviewRequest
import io.springkit.workflow.application.OpenReviewResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReadyReviewRequest
import io.springkit.workflow.application.ReadyReviewResponse
import io.springkit.workflow.application.ReplyReviewThreadRequest
import io.springkit.workflow.application.ReplyReviewThreadResponse
import io.springkit.workflow.application.ResolveReviewThreadRequest
import io.springkit.workflow.application.ResolveReviewThreadResponse
import io.springkit.workflow.application.ReviewPort
import io.springkit.workflow.application.UpdateReviewRequest
import io.springkit.workflow.application.UpdateReviewResponse
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** GitHub pull request 응답에서 Workflow 도메인 객체를 복원합니다. */
fun interface GithubPullRequestResolver {
  fun resolve(provider: GithubPullRequest, previous: PullRequest?): PullRequest
}

/** `gh pr view`가 반환하는 Workflow에 필요한 pull request 정보입니다. */
@Serializable
data class GithubPullRequest(
    val number: Long = 0,
    val title: String = "",
    val body: String = "",
    val state: String = "",
    val isDraft: Boolean = false,
    val baseRefName: String = "",
    val headRefName: String = "",
    val headRefOid: String? = null,
    val reviewDecision: String? = null,
    val author: GithubUser? = null,
    val comments: List<GithubComment> = emptyList(),
    val reviews: List<GithubReview> = emptyList(),
)

@Serializable data class GithubUser(val login: String = "", val name: String? = null)

@Serializable
data class GithubComment(
    val id: String = "",
    val body: String = "",
    val author: GithubUser? = null,
    val createdAt: String? = null,
    val path: String? = null,
    val line: Int? = null,
)

@Serializable
data class GithubReview(
    val id: String = "",
    val body: String = "",
    val state: String = "",
    val author: GithubUser? = null,
    val submittedAt: String? = null,
)

/** 설치된 `gh` 실행 파일에 pull request와 Review 동작을 위임하는 [ReviewPort] 구현입니다. */
class GithubReviewAdapter(
    private val repositoryRoot: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
    private val pullRequestResolver: GithubPullRequestResolver = DefaultGithubPullRequestResolver,
    private val currentPullRequest: (String) -> PullRequest? = { null },
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ReviewPort {
  override fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse> {
    val created =
        execute(
            listOf(
                "gh",
                "pr",
                "create",
                "--draft",
                "--title",
                request.title,
                "--body",
                request.body,
                "--base",
                request.base,
                "--head",
                request.branch,
            ),
            request.subTaskId,
        )
            ?: return failure(
                "GITHUB_CREATE_FAILED",
                "GitHub pull request를 생성할 수 없습니다.",
                request.subTaskId,
            )
    val reference =
        pullRequestReference(created.stdout)
            ?: return failure(
                "GITHUB_RESPONSE_INVALID",
                "pull request 생성 결과에 번호가 없습니다.",
                request.subTaskId,
            )
    val provider =
        fetch(reference)
            ?: return failure("GITHUB_RESPONSE_INVALID", "생성된 pull request를 조회할 수 없습니다.", reference)
    val pullRequest =
        pullRequestResolver.resolve(provider, null).let {
          it.copy(
              id = provider.number.toString(),
              subTaskId = request.subTaskId,
              title = request.title,
              body = request.body,
              base = request.base,
              state = PullRequestState.DRAFT,
              risk = request.risk,
              exposure = request.exposure,
              featureFlagId = request.featureFlagId,
              reviewRevision = request.reviewRevision,
              changeRevision = request.changeRevision,
          )
        }
    return PortResult.Success(
        OpenReviewResponse(pullRequest, receipt("open", pullRequest.id)),
    )
  }

  override fun get(request: GetReviewRequest): PortResult<GetReviewResponse> {
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_GET_FAILED",
                "GitHub pull request를 조회할 수 없습니다.",
                request.pullRequestId,
            )
    return PortResult.Success(GetReviewResponse(resolve(provider)))
  }

  override fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse> {
    val persisted = currentPullRequest(request.pullRequestId)
    if (persisted != null && persisted.reviewRevision.id != request.expectedReviewRevisionId) {
      return failure(
          "STALE_REVISION",
          "요청한 review revision이 현재 상태와 다릅니다.",
          request.pullRequestId,
      )
    }
    if (request.body != null || request.base != null) {
      val command = buildList {
        addAll(listOf("gh", "pr", "edit", request.pullRequestId))
        request.body?.let {
          add("--body")
          add(it)
        }
        request.base?.let {
          add("--base")
          add(it)
        }
      }
      if (
          execute(
              command,
              request.pullRequestId,
          ) == null
      ) {
        return failure(
            "GITHUB_UPDATE_FAILED",
            "GitHub pull request를 갱신할 수 없습니다.",
            request.pullRequestId,
        )
      }
    }
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_UPDATE_FAILED",
                "갱신된 GitHub pull request를 조회할 수 없습니다.",
                request.pullRequestId,
            )
    val pullRequest =
        resolve(provider).let { current ->
          current.copy(
              body = request.body ?: current.body,
              base = request.base ?: current.base,
              reviewRevision = request.reviewRevision ?: current.reviewRevision,
              changeRevision = request.changeRevision ?: current.changeRevision,
          )
        }
    return PortResult.Success(
        UpdateReviewResponse(
            pullRequest = pullRequest,
            codeChanged = request.changeRevision != null,
            change = receipt("update", request.pullRequestId),
        )
    )
  }

  override fun comment(request: AddReviewCommentRequest): PortResult<AddReviewCommentResponse> {
    val persisted = currentPullRequest(request.pullRequestId)
    if (persisted != null) {
      if (persisted.reviewRevision.id != request.reviewRevisionId) {
        return failure(
            "STALE_REVISION",
            "요청한 review revision이 현재 상태와 다릅니다.",
            request.pullRequestId,
        )
      }
      if (
          persisted.reviewRevision.threads.any { thread ->
            thread.comments.any { comment -> comment.id == request.comment.id }
          }
      ) {
        return failure(
            "REVIEW_COMMENT_CONFLICT",
            "같은 review comment ID가 이미 존재합니다.",
            request.comment.id,
        )
      }
    }
    val body = request.comment.body.withReviewLevel(request.level)
    val command =
        if (request.comment.path == null) {
          listOf("gh", "pr", "comment", request.pullRequestId, "--body", body)
        } else {
          val current =
              currentPullRequest(request.pullRequestId)
                  ?: return failure(
                      "GITHUB_REVIEW_STATE_REQUIRED",
                      "코드 줄 코멘트에 필요한 현재 변경 revision이 없습니다.",
                      request.pullRequestId,
                  )
          listOf(
              "gh",
              "api",
              "--method",
              "POST",
              "repos/{owner}/{repo}/pulls/${request.pullRequestId}/comments",
              "-f",
              "body=$body",
              "-f",
              "commit_id=${current.changeRevision.diff.identity}",
              "-f",
              "path=${request.comment.path}",
              "-F",
              "line=${request.comment.line}",
              "-f",
              "side=RIGHT",
          )
        }
    execute(command, request.pullRequestId)
        ?: return failure(
            "GITHUB_COMMENT_FAILED",
            "GitHub pull request에 코멘트를 추가할 수 없습니다.",
            request.pullRequestId,
        )
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_COMMENT_FAILED",
                "추가된 GitHub 코멘트를 조회할 수 없습니다.",
                request.pullRequestId,
            )
    val pullRequest = resolve(provider)
    val threadId = request.comment.id
    val thread = ReviewThread(threadId, request.level, listOf(request.comment))
    val revision = nextReviewRevision(pullRequest, pullRequest.reviewRevision.threads + thread)
    return PortResult.Success(
        AddReviewCommentResponse(revision, threadId, receipt("comment", request.pullRequestId)),
    )
  }

  override fun reply(request: ReplyReviewThreadRequest): PortResult<ReplyReviewThreadResponse> {
    val persisted = currentPullRequest(request.pullRequestId)
    val currentThread =
        persisted?.reviewRevision?.threads?.firstOrNull { thread ->
          thread.id == request.threadId
        }
    if (persisted == null || currentThread == null) {
      return failure(
          "GITHUB_REVIEW_STATE_REQUIRED",
          "review thread의 영속 상태를 확인할 수 없습니다.",
          request.threadId,
      )
    }
    if (persisted.reviewRevision.id != request.reviewRevisionId) {
      return failure(
          "STALE_REVISION",
          "요청한 review revision이 현재 상태와 다릅니다.",
          request.pullRequestId,
      )
    }
    val markedComment =
        request.comment.copy(body = request.comment.body.withReviewLevel(currentThread.level))
    val query =
        "mutation(${'$'}subjectId:ID!,${'$'}body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:${'$'}subjectId,body:${'$'}body}){comment{id}}}"
    if (
        execute(
            listOf(
                "gh",
                "api",
                "graphql",
                "-f",
                "query=$query",
                "-f",
                "subjectId=${request.threadId}",
                "-f",
                "body=${markedComment.body}",
            ),
            request.pullRequestId,
        ) == null
    ) {
      return failure("GITHUB_REPLY_FAILED", "GitHub review thread에 답변할 수 없습니다.", request.threadId)
    }
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_REPLY_FAILED",
                "답변된 GitHub review thread를 조회할 수 없습니다.",
                request.threadId,
            )
    val pullRequest = resolve(provider)
    val repliedThreads =
        pullRequest.reviewRevision.threads.map { thread ->
          if (thread.id == request.threadId) thread.reply(markedComment) else thread
        }
    val revision =
        nextReviewRevision(
            pullRequest,
            if (repliedThreads.any { it.id == request.threadId }) repliedThreads
            else
                repliedThreads +
                    ReviewThread(request.threadId, currentThread.level, listOf(markedComment)),
        )
    return PortResult.Success(
        ReplyReviewThreadResponse(revision, receipt("reply", request.threadId)),
    )
  }

  override fun resolve(
      request: ResolveReviewThreadRequest
  ): PortResult<ResolveReviewThreadResponse> {
    val current = currentPullRequest(request.pullRequestId)
    val currentThread = current?.reviewRevision?.threads?.firstOrNull { it.id == request.threadId }
    if (currentThread == null) {
      return failure(
          "GITHUB_REVIEW_STATE_REQUIRED",
          "review thread의 영속 상태를 확인할 수 없습니다.",
          request.threadId,
      )
    }
    if (current.reviewRevision.id != request.reviewRevisionId) {
      return failure(
          "STALE_REVISION",
          "요청한 review revision이 현재 상태와 다릅니다.",
          request.pullRequestId,
      )
    }
    if (currentThread.requiresHumanResolution && !request.actor.isHuman) {
      return failure(
          "HUMAN_REQUIRED",
          "사람이 작성한 review thread는 사람만 해결할 수 있습니다.",
          request.threadId,
      )
    }
    val query =
        "mutation(${'$'}threadId:ID!){resolveReviewThread(input:{threadId:${'$'}threadId}){thread{id}}}"
    if (
        execute(
            listOf(
                "gh",
                "api",
                "graphql",
                "-f",
                "query=$query",
                "-f",
                "threadId=${request.threadId}",
            ),
            request.pullRequestId,
        ) == null
    ) {
      return failure("GITHUB_RESOLVE_FAILED", "GitHub review thread를 해결할 수 없습니다.", request.threadId)
    }
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_RESOLVE_FAILED",
                "해결된 GitHub review thread를 조회할 수 없습니다.",
                request.threadId,
            )
    val pullRequest = resolve(provider)
    val revision =
        nextReviewRevision(
            pullRequest,
            pullRequest.reviewRevision.threads.map { thread ->
              if (thread.id == request.threadId) thread.resolve(request.actor) else thread
            },
        )
    return PortResult.Success(
        ResolveReviewThreadResponse(revision, receipt("resolve", request.threadId)),
    )
  }

  override fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse> {
    if (!request.actor.isHuman) {
      return failure(
          "HUMAN_REQUIRED",
          "pull request Ready 전환은 사람만 수행할 수 있습니다.",
          request.pullRequestId,
      )
    }
    if (
        execute(listOf("gh", "pr", "ready", request.pullRequestId), request.pullRequestId) == null
    ) {
      return failure(
          "GITHUB_READY_FAILED",
          "GitHub pull request를 Ready 상태로 전환할 수 없습니다.",
          request.pullRequestId,
      )
    }
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_READY_FAILED",
                "Ready 상태의 GitHub pull request를 조회할 수 없습니다.",
                request.pullRequestId,
            )
    val pullRequest = resolve(provider).copy(state = PullRequestState.READY)
    return PortResult.Success(
        ReadyReviewResponse(pullRequest, receipt("ready", request.pullRequestId))
    )
  }

  override fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse> {
    if (!request.actor.isHuman) {
      return failure("HUMAN_REQUIRED", "pull request 승인은 사람만 수행할 수 있습니다.", request.pullRequestId)
    }
    if (
        execute(
            listOf("gh", "pr", "review", request.pullRequestId, "--approve"),
            request.pullRequestId,
        ) == null
    ) {
      return failure(
          "GITHUB_APPROVE_FAILED",
          "GitHub pull request를 승인할 수 없습니다.",
          request.pullRequestId,
      )
    }
    val provider =
        fetch(request.pullRequestId)
            ?: return failure(
                "GITHUB_APPROVE_FAILED",
                "승인된 GitHub pull request를 조회할 수 없습니다.",
                request.pullRequestId,
            )
    val pullRequest = resolve(provider)
    if (
        pullRequest.changeRevision.id != request.changeRevisionId ||
            pullRequest.changeRevision.diff.identity != request.diffIdentity
    ) {
      return failure(
          "STALE_REVISION",
          "요청한 change revision이 현재 pull request와 다릅니다.",
          request.pullRequestId,
      )
    }
    val approval =
        Approval(
            id = "github-approval-${request.pullRequestId}",
            actor = request.actor,
            changeRevisionId = request.changeRevisionId,
            diffIdentity = request.diffIdentity,
            approvedAtEpochMillis = nowEpochMillis(),
        )
    return PortResult.Success(
        ApproveReviewResponse(
            pullRequest.copy(state = PullRequestState.APPROVED, approval = approval),
            approval,
            receipt("approve", request.pullRequestId),
        )
    )
  }

  private fun fetch(reference: String): GithubPullRequest? {
    val result =
        execute(
            listOf(
                "gh",
                "pr",
                "view",
                reference,
                "--json",
                "number,title,body,state,isDraft,baseRefName,headRefName,headRefOid,reviewDecision,author,comments,reviews",
            ),
            reference,
        ) ?: return null
    return try {
      json.decodeFromString<GithubPullRequest>(result.stdout)
    } catch (_: SerializationException) {
      null
    }
  }

  private fun execute(
      command: List<String>,
      target: String,
  ): io.springkit.workflow.common.CommandResult? {
    val result =
        try {
          commandRunner.run(command, repositoryRoot)
        } catch (failure: Exception) {
          lastFailure =
              PortResult.Failure(
                  PortError(
                      code = "GITHUB_COMMAND_FAILED",
                      message =
                          "gh 명령을 실행할 수 없습니다: ${failure.message ?: failure::class.simpleName}",
                      retryable = true,
                      target = target,
                  )
              )
          return null
        }
    if (result.exitCode == 0) {
      lastFailure = null
      return result
    }
    lastFailure =
        PortResult.Failure(
            PortError(
                code = "GITHUB_COMMAND_FAILED",
                message =
                    result.stderr
                        .trim()
                        .ifBlank { result.stdout.trim() }
                        .ifBlank {
                          "gh 명령이 종료 코드 ${result.exitCode}로 실패했습니다."
                        },
                retryable = result.exitCode == 2,
                target = target,
            )
        )
    return null
  }

  private var lastFailure: PortResult.Failure? = null

  private fun resolve(provider: GithubPullRequest): PullRequest =
      pullRequestResolver.resolve(provider, currentPullRequest(provider.number.toString()))

  private fun <T> failure(code: String, message: String, target: String): PortResult<T> =
      PortResult.Failure(lastFailure?.error ?: PortError(code, message, target = target)).also {
        lastFailure = null
      }

  private fun receipt(operation: String, target: String): ChangeReceipt =
      ChangeReceipt("github-$operation-$target", "github-$operation")

  private fun nextReviewRevision(
      pullRequest: PullRequest,
      threads: List<ReviewThread>,
  ): ReviewRevision {
    val number = pullRequest.reviewRevision.number + 1
    val occurredAt = nowEpochMillis()
    return pullRequest.reviewRevision.copy(
        id = "github-review-${pullRequest.id}-$number-$occurredAt",
        number = number,
        threads = threads,
        createdAtEpochMillis = occurredAt,
    )
  }

  private fun pullRequestReference(output: String): String? =
      output.trim().lineSequence().lastOrNull()?.trim()?.substringAfterLast('/')?.takeIf {
        it.isNotBlank() && it.all(Char::isDigit)
      }

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}

private object DefaultGithubPullRequestResolver : GithubPullRequestResolver {
  override fun resolve(provider: GithubPullRequest, previous: PullRequest?): PullRequest {
    if (previous != null) {
      val providerBody = provider.body.takeIf { it.isNotBlank() }
      val providerDiffIdentity = provider.headRefOid?.takeIf { it.isNotBlank() }
      val codeChanged =
          providerDiffIdentity != null &&
              providerDiffIdentity != previous.changeRevision.diff.identity
      val bodyChanged = providerBody != null && providerBody != previous.body
      val nextChangeRevision =
          if (codeChanged) {
            previous.changeRevision.copy(
                id = "github-change-${provider.number}-${previous.changeRevision.number + 1}",
                number = previous.changeRevision.number + 1,
                diff = previous.changeRevision.diff.copy(identity = providerDiffIdentity),
            )
          } else {
            previous.changeRevision
          }
      val nextReviewRevision =
          if (bodyChanged) {
            previous.reviewRevision.copy(
                id = "github-review-${provider.number}-${previous.reviewRevision.number + 1}",
                number = previous.reviewRevision.number + 1,
                body = providerBody,
            )
          } else {
            previous.reviewRevision
          }
      return previous.copy(
          title = provider.title.ifBlank { previous.title },
          body = providerBody ?: previous.body,
          base = provider.baseRefName.ifBlank { previous.base },
          state = providerState(provider),
          reviewRevision = nextReviewRevision,
          changeRevision = nextChangeRevision,
          approval = if (codeChanged) null else previous.approval,
          ci = if (codeChanged) CiStatus.PENDING else previous.ci,
          aiReview = if (codeChanged) AiReviewStatus.PENDING else previous.aiReview,
      )
    }
    val number = provider.number.takeIf { it > 0 }?.toString() ?: "github-pr"
    val body = provider.body.ifBlank { provider.title.ifBlank { "GitHub pull request $number" } }
    val title = provider.title.ifBlank { "GitHub pull request $number" }
    val changeId = "github-change-$number"
    val reviewId = "github-review-$number"
    val diffIdentity = provider.headRefOid ?: "github-diff-$number"
    return PullRequest(
        id = number,
        subTaskId = provider.headRefName.ifBlank { "github-$number" },
        title = title,
        body = body,
        base = provider.baseRefName.ifBlank { "main" },
        state = providerState(provider),
        reviewRevision = ReviewRevision(reviewId, 1, body),
        changeRevision = ChangeRevision(changeId, 1, Diff(diffIdentity)),
    )
  }

  private fun providerState(provider: GithubPullRequest): PullRequestState =
      when {
        provider.state.equals("MERGED", ignoreCase = true) -> PullRequestState.MERGED
        !provider.state.equals("OPEN", ignoreCase = true) -> PullRequestState.CLOSED
        provider.isDraft -> PullRequestState.DRAFT
        provider.reviewDecision.equals("APPROVED", ignoreCase = true) -> PullRequestState.APPROVED
        else -> PullRequestState.REVIEW
      }
}

private fun String.withReviewLevel(level: ReviewLevel): String {
  val agentMarked = startsWith("[Agent]")
  val withoutAgent = if (agentMarked) removePrefix("[Agent]").trimStart() else this
  val withoutLevel = withoutAgent.replaceFirst(Regex("^\\[(R|C|A)]\\s*"), "")
  val levelMarked = "[${level.name}] ${withoutLevel.trimStart()}"
  return if (agentMarked) "[Agent] $levelMarked" else levelMarked
}
