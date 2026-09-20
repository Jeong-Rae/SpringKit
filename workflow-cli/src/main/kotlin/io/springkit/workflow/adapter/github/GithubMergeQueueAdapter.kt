package io.springkit.workflow.adapter.github

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.EnqueueMergeRequest
import io.springkit.workflow.application.EnqueueMergeResponse
import io.springkit.workflow.application.GetMergeQueueRequest
import io.springkit.workflow.application.GetMergeQueueResponse
import io.springkit.workflow.application.MergeQueueMergeRequest
import io.springkit.workflow.application.MergeQueueMergeResponse
import io.springkit.workflow.application.MergeQueuePort
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.MergeQueueState
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** GitHub pull request 응답에서 Workflow Merge Queue 항목을 복원합니다. */
fun interface GithubMergeQueueEntryResolver {
  fun resolve(provider: GithubMergeQueuePullRequest, fallback: MergeQueueEntry): MergeQueueEntry
}

/** `gh pr view`와 `gh pr list`가 반환하는 Merge Queue 관련 정보입니다. */
@Serializable
data class GithubMergeQueuePullRequest(
    val number: Long = 0,
    val state: String = "",
    val isDraft: Boolean = false,
    val mergeStateStatus: String? = null,
    val reviewDecision: String? = null,
    val headRefName: String? = null,
    val headRefOid: String? = null,
    val statusCheckRollup: List<GithubMergeQueueCheck>? = null,
    val isInMergeQueue: Boolean? = null,
    val autoMergeRequest: GithubMergeQueueAutoMergeRequest? = null,
    val mergedAt: String? = null,
    val mergeCommit: GithubMergeQueueCommit? = null,
)

@Serializable
data class GithubMergeQueueCheck(
    val name: String = "",
    val context: String? = null,
    val status: String? = null,
    val state: String? = null,
    val conclusion: String? = null,
    val databaseId: Long? = null,
)

@Serializable data class GithubMergeQueueAutoMergeRequest(val enabledAt: String? = null)

@Serializable data class GithubMergeQueueCommit(val oid: String? = null)

/**
 * `gh` 실행 결과를 [MergeQueuePort] 계약으로 변환하는 GitHub outbound adapter입니다.
 *
 * [entryLookup]과 [entryPersister]는 프로세스 외부의 Workflow Store에 연결해야 하며, 어댑터는 Merge Queue 항목을 메모리에 보관하지
 * 않습니다.
 */
class GithubMergeQueueAdapter(
    private val repositoryRoot: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
    private val entryResolver: GithubMergeQueueEntryResolver = DefaultGithubMergeQueueEntryResolver,
    private val entryLookup: (String) -> MergeQueueEntry? = { null },
    private val entryPersister: (MergeQueueEntry) -> Unit = {},
) : MergeQueuePort {
  override fun enqueue(request: EnqueueMergeRequest): PortResult<EnqueueMergeResponse> {
    val command = listOf("gh", "pr", "merge", request.pullRequestId, "--squash", "--auto")
    val result = run(command, request.pullRequestId) ?: return lastFailure(request.pullRequestId)
    val fallback =
        MergeQueueEntry(
            id = entryId(request.pullRequestId),
            subTaskId = request.subTaskId,
            pullRequestId = request.pullRequestId,
            changeRevisionId = request.changeRevisionId,
            state = MergeQueueState.QUEUED,
        )
    val entry =
        entryResolver.resolve(
            GithubMergeQueuePullRequest(number = number(request.pullRequestId)),
            fallback,
        )
    entryPersister(entry)
    return PortResult.Success(
        EnqueueMergeResponse(
            entry = entry,
            change =
                ChangeReceipt(
                    id = "github-merge-queue-enqueue-${request.pullRequestId}",
                    operation = "github-merge-queue-enqueue",
                ),
        )
    )
  }

  override fun get(request: GetMergeQueueRequest): PortResult<GetMergeQueueResponse> {
    val reference = request.pullRequestId ?: request.subTaskId ?: "all"
    val command =
        if (request.pullRequestId != null) {
          listOf("gh", "pr", "view", reference, "--json", JSON_FIELDS)
        } else if (request.subTaskId != null) {
          listOf(
              "gh",
              "pr",
              "list",
              "--head",
              reference,
              "--state",
              "all",
              "--json",
              JSON_FIELDS,
          )
        } else {
          listOf("gh", "pr", "list", "--state", "all", "--json", JSON_FIELDS)
        }
    val result = run(command, reference) ?: return lastFailure(reference)
    return try {
      val providers =
          if (request.pullRequestId != null) {
            listOf(json.decodeFromString<GithubMergeQueuePullRequest>(result.stdout))
          } else {
            json.decodeFromString<List<GithubMergeQueuePullRequest>>(result.stdout)
          }
      val entries =
          providers
              .filter { provider ->
                (request.pullRequestId == null ||
                    provider.number.toString() == request.pullRequestId) &&
                    (request.subTaskId == null || provider.headRefName == request.subTaskId)
              }
              .map { provider ->
                val pullRequestId = provider.number.toString()
                val fallback = fallbackEntry(provider, request, pullRequestId)
                val entry = entryResolver.resolve(provider, fallback)
                entry
              }
      PortResult.Success(GetMergeQueueResponse(entries))
    } catch (_: SerializationException) {
      PortResult.Failure(
          PortError(
              code = "GITHUB_MERGE_QUEUE_RESPONSE_INVALID",
              message = "GitHub Merge Queue 응답 JSON을 해석할 수 없습니다.",
              target = reference,
          )
      )
    }
  }

  override fun merge(request: MergeQueueMergeRequest): PortResult<MergeQueueMergeResponse> {
    val persistedEntry =
        entryLookup(request.entryId) ?: entryLookup(pullRequestIdFromEntry(request.entryId))
    val reference = persistedEntry?.pullRequestId ?: pullRequestIdFromEntry(request.entryId)
    val beforeView =
        run(listOf("gh", "pr", "view", reference, "--json", JSON_FIELDS), reference)
            ?: return lastFailure(reference)
    val beforeProvider = decode(beforeView.stdout, reference) ?: return lastFailure(reference)
    val pullRequestId = beforeProvider.number.takeIf { it > 0 }?.toString() ?: reference
    val previous = persistedEntry ?: entryLookup(pullRequestId)
    if (previous == null) {
      return PortResult.Failure(
          PortError(
              code = "MERGE_QUEUE_STATE_REQUIRED",
              message = "Merge Queue 항목의 영속 상태를 확인할 수 없습니다.",
              target = pullRequestId,
          )
      )
    }
    val headRefName = beforeProvider.headRefName?.takeIf { it.isNotBlank() }
    val headRefOid = beforeProvider.headRefOid?.takeIf { it.isNotBlank() }
    if (headRefName == null || headRefOid == null) {
      return PortResult.Failure(
          PortError(
              code = "GITHUB_MERGE_QUEUE_RESPONSE_INVALID",
              message = "GitHub pull request의 branch 또는 revision이 없습니다.",
              target = pullRequestId,
          )
      )
    }
    val beforeFallback =
        previous.copy(
            id = request.entryId,
            subTaskId = headRefName,
            pullRequestId = pullRequestId,
            state = beforeProvider.toMergeQueueState(),
            validations = beforeProvider.validations(),
        )
    val beforeEntry = entryResolver.resolve(beforeProvider, beforeFallback)
    if (beforeEntry.changeRevisionId != request.expectedChangeRevisionId) {
      return PortResult.Failure(
          PortError(
              code = "STALE_REVISION",
              message = "요청한 change revision과 GitHub pull request revision이 다릅니다.",
              target = pullRequestId,
          )
      )
    }
    val mergeResult =
        run(
            listOf(
                "gh",
                "pr",
                "merge",
                pullRequestId,
                "--squash",
                "--match-head-commit",
                headRefOid,
            ),
            request.entryId,
            staleRevision = true,
        ) ?: return lastFailure(request.entryId)
    val viewResult =
        run(listOf("gh", "pr", "view", pullRequestId, "--json", JSON_FIELDS), pullRequestId)
            ?: return lastFailure(pullRequestId, mergeResult)
    val provider =
        decode(viewResult.stdout, pullRequestId)
            ?: return PortResult.Failure(
                PortError(
                    code = "GITHUB_MERGE_QUEUE_RESPONSE_INVALID",
                    message = "GitHub squash merge 응답 JSON을 해석할 수 없습니다.",
                    target = pullRequestId,
                ),
                pendingMergeReceipt(request.entryId),
            )
    val commit = provider.mergeCommit?.oid?.takeIf { it.isNotBlank() }
    if (!provider.state.equals("MERGED", ignoreCase = true) || commit == null) {
      return PortResult.Failure(
          PortError(
              code = "GITHUB_MERGE_QUEUE_NOT_MERGED",
              message = "GitHub pull request가 squash merge되지 않았습니다.",
              target = pullRequestId,
          ),
          ChangeReceipt(
              id = "github-merge-queue-merge-${request.entryId}",
              operation = "github-merge-queue-merge",
              status = io.springkit.workflow.application.ChangeStatus.PENDING,
          ),
      )
    }
    val fallback =
        beforeEntry.copy(
            pullRequestId = pullRequestId,
            changeRevisionId = request.expectedChangeRevisionId,
            state = MergeQueueState.MERGED,
        )
    val entry = entryResolver.resolve(provider, fallback).copy(state = MergeQueueState.MERGED)
    entryPersister(entry)
    val integration =
        Integration(
            subTaskId = entry.subTaskId,
            state = IntegrationState.MERGED,
            mergeQueue = entry,
            mainRevision = commit,
            squashCommit = commit,
        )
    return PortResult.Success(
        MergeQueueMergeResponse(
            integration = integration,
            change =
                ChangeReceipt(
                    id = "github-merge-queue-merge-${request.entryId}",
                    operation = "github-merge-queue-merge",
                    beforeRevision = provider.headRefOid,
                    afterRevision = commit,
                ),
        )
    )
  }

  private fun fallbackEntry(
      provider: GithubMergeQueuePullRequest,
      request: GetMergeQueueRequest,
      pullRequestId: String,
  ): MergeQueueEntry {
    val previous = entryLookup(pullRequestId)
    return (previous
            ?: MergeQueueEntry(
                id = entryId(pullRequestId),
                subTaskId = provider.headRefName ?: request.subTaskId ?: "github-$pullRequestId",
                pullRequestId = pullRequestId,
                changeRevisionId = provider.headRefOid ?: "github-change-$pullRequestId",
                state = MergeQueueState.QUEUED,
            ))
        .copy(
            state = provider.toMergeQueueState(),
            validations = provider.validations(),
        )
  }

  private fun run(
      command: List<String>,
      target: String,
      staleRevision: Boolean = false,
  ): io.springkit.workflow.common.CommandResult? {
    val result =
        try {
          commandRunner.run(command, repositoryRoot)
        } catch (failure: Exception) {
          lastFailure =
              PortResult.Failure(
                  PortError(
                      code = "GITHUB_MERGE_QUEUE_FAILED",
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
    val output = result.stderr.trim().ifBlank { result.stdout.trim() }
    val code =
        if (
            staleRevision &&
                output.contains("head", ignoreCase = true) &&
                (output.contains("match", ignoreCase = true) ||
                    output.contains("stale", ignoreCase = true))
        ) {
          "STALE_REVISION"
        } else {
          "GITHUB_MERGE_QUEUE_FAILED"
        }
    lastFailure =
        PortResult.Failure(
            PortError(
                code = code,
                message = output.ifBlank { "gh 명령이 종료 코드 ${result.exitCode}로 실패했습니다." },
                retryable = result.exitCode == 2,
                target = target,
            )
        )
    return null
  }

  private fun <T> lastFailure(
      target: String,
      result: io.springkit.workflow.common.CommandResult? = null,
  ): PortResult<T> =
      lastFailure
          ?: PortResult.Failure(
              PortError(
                  code = "GITHUB_MERGE_QUEUE_FAILED",
                  message =
                      result?.stderr?.trim().orEmpty().ifBlank {
                        "gh 명령을 실행할 수 없습니다."
                      },
                  target = target,
              )
          )

  private var lastFailure: PortResult.Failure? = null

  private fun decode(stdout: String, target: String): GithubMergeQueuePullRequest? =
      try {
        json.decodeFromString<GithubMergeQueuePullRequest>(stdout)
      } catch (_: SerializationException) {
        lastFailure =
            PortResult.Failure(
                PortError(
                    code = "GITHUB_MERGE_QUEUE_RESPONSE_INVALID",
                    message = "GitHub Merge Queue 응답 JSON을 해석할 수 없습니다.",
                    target = target,
                )
            )
        null
      }

  private fun pendingMergeReceipt(entryId: String): ChangeReceipt =
      ChangeReceipt(
          id = "github-merge-queue-merge-$entryId",
          operation = "github-merge-queue-merge",
          status = io.springkit.workflow.application.ChangeStatus.PENDING,
      )

  private companion object {
    const val JSON_FIELDS =
        "number,state,isDraft,mergeStateStatus,reviewDecision,headRefName,headRefOid,statusCheckRollup,isInMergeQueue,autoMergeRequest,mergedAt,mergeCommit"

    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    fun entryId(pullRequestId: String): String = "github-merge-queue-$pullRequestId"

    fun number(reference: String): Long = reference.toLongOrNull() ?: 0

    fun pullRequestIdFromEntry(entryId: String): String =
        entryId.removePrefix("github-merge-queue-").ifBlank { entryId }
  }
}

private object DefaultGithubMergeQueueEntryResolver : GithubMergeQueueEntryResolver {
  override fun resolve(
      provider: GithubMergeQueuePullRequest,
      fallback: MergeQueueEntry,
  ): MergeQueueEntry = fallback
}

private fun GithubMergeQueuePullRequest.toMergeQueueState(): MergeQueueState =
    when {
      state.equals("MERGED", ignoreCase = true) -> MergeQueueState.MERGED
      state.equals("CLOSED", ignoreCase = true) -> MergeQueueState.FAILED
      statusCheckRollup.orEmpty().any { it.toValidationStatus() == ValidationStatus.FAILED } ->
          MergeQueueState.FAILED
      isInMergeQueue == true -> MergeQueueState.VALIDATING
      mergeStateStatus.equals("CLEAN", ignoreCase = true) -> MergeQueueState.PASSED
      autoMergeRequest != null -> MergeQueueState.QUEUED
      else -> MergeQueueState.QUEUED
    }

private fun GithubMergeQueuePullRequest.validations(): List<Validation> =
    statusCheckRollup.orEmpty().mapIndexed { index, check ->
      val status = check.toValidationStatus()
      Validation(
          id = check.databaseId?.toString() ?: "github-validation-$index",
          name = check.name.ifBlank { check.context ?: "check-$index" },
          status = status,
          revision = headRefOid,
          message = if (status == ValidationStatus.FAILED) check.failureMessage() else null,
      )
    }

private fun GithubMergeQueueCheck.toValidationStatus(): ValidationStatus {
  val conclusionValue = conclusion.orEmpty().uppercase()
  val statusValue = (status ?: state).orEmpty().uppercase()
  return when {
    conclusionValue in setOf("SUCCESS", "NEUTRAL", "SKIPPED") -> ValidationStatus.PASSED
    conclusionValue.isNotBlank() -> ValidationStatus.FAILED
    statusValue in setOf("QUEUED", "REQUESTED", "WAITING", "PENDING") -> ValidationStatus.PENDING
    statusValue in setOf("IN_PROGRESS", "EXPECTED") -> ValidationStatus.RUNNING
    statusValue == "COMPLETED" -> ValidationStatus.PASSED
    else -> ValidationStatus.PENDING
  }
}

private fun GithubMergeQueueCheck.failureMessage(): String =
    conclusion.orEmpty().ifBlank { state.orEmpty() }.ifBlank { "GitHub 검증이 실패했습니다." }
