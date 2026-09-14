package io.springkit.workflow.adapter.git

import io.springkit.workflow.application.AbortRestackRequest
import io.springkit.workflow.application.AbortRestackResponse
import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.ContinueRestackRequest
import io.springkit.workflow.application.ContinueRestackResponse
import io.springkit.workflow.application.CreateBranchRequest
import io.springkit.workflow.application.CreateBranchResponse
import io.springkit.workflow.application.CreateWorktreeRequest
import io.springkit.workflow.application.CreateWorktreeResponse
import io.springkit.workflow.application.GitInspectRequest
import io.springkit.workflow.application.GitInspectResponse
import io.springkit.workflow.application.GitPort
import io.springkit.workflow.application.GitStatus
import io.springkit.workflow.application.MainRevisionRequest
import io.springkit.workflow.application.MainRevisionResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.RemoveBranchRequest
import io.springkit.workflow.application.RemoveBranchResponse
import io.springkit.workflow.application.RestackRequest
import io.springkit.workflow.application.RestackResponse
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.WorkspaceId
import java.nio.file.Path
import java.security.MessageDigest

/** 설치된 git 실행 파일에 Git 동작을 위임하는 [GitPort] 구현입니다. */
class LocalGitAdapter(
    private val repositoryRoot: Path,
    private val workspacePath: (WorkspaceId) -> Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
) : GitPort {
  override fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse> {
    val fetch = run(listOf("git", "fetch", request.remote, request.branch), repositoryRoot)
    if (fetch is Execution.Failure) return fetch.result

    return findRevision(request)?.let { PortResult.Success(MainRevisionResponse(it)) }
        ?: failure(
            code = "MAIN_REVISION_UNAVAILABLE",
            message = "기준 revision을 확인할 수 없습니다: ${request.remote}/${request.branch}",
            target = request.branch,
        )
  }

  override fun inspect(request: GitInspectRequest): PortResult<GitInspectResponse> {
    val path = resolveWorkspace(request.workspaceId) ?: return workspaceFailure(request.workspaceId)
    val revision = run(listOf("git", "rev-parse", "HEAD"), path)
    if (revision is Execution.Failure) return revision.result
    val revisionResult = (revision as Execution.Success).result
    val status = run(listOf("git", "status", "--porcelain=v1", "--untracked-files=all"), path)
    if (status is Execution.Failure) return status.result
    val statusResult = (status as Execution.Success).result
    val diff = run(listOf("git", "diff", "--binary", "HEAD"), path)
    if (diff is Execution.Failure) return diff.result
    val diffResult = (diff as Execution.Success).result
    val untracked = run(listOf("git", "ls-files", "--others", "--exclude-standard", "-z"), path)
    if (untracked is Execution.Failure) return untracked.result
    val untrackedPaths =
        (untracked as Execution.Success).result.stdout.split('\u0000').filter(String::isNotBlank)
    val untrackedHashes = buildString {
      untrackedPaths.forEach { untrackedPath ->
        val hash = run(listOf("git", "hash-object", "--", untrackedPath), path)
        if (hash is Execution.Failure) return hash.result
        append(untrackedPath).append('\u0000').append((hash as Execution.Success).result.stdout)
      }
    }

    val conflicts =
        if (
            statusResult.stdout.lineSequence().any {
              it.take(2) in setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")
            }
        ) {
          val result = run(listOf("git", "diff", "--name-only", "--diff-filter=U"), path)
          if (result is Execution.Failure) return result.result
          (result as Execution.Success).result.stdout.lines().filter(String::isNotBlank)
        } else {
          emptyList()
        }
    val revisionValue = revisionResult.stdout.trim()
    if (revisionValue.isBlank()) {
      return failure("GIT_INSPECT_FAILED", "HEAD revision이 비어 있습니다.", request.workspaceId)
    }
    val statusOutput = statusResult.stdout
    return PortResult.Success(
        GitInspectResponse(
            GitStatus(
                revision = revisionValue,
                fingerprint = fingerprint(revisionValue, diffResult.stdout, untrackedHashes),
                dirty = statusOutput.isNotBlank(),
                conflicts = conflicts,
            )
        )
    )
  }

  override fun createBranch(request: CreateBranchRequest): PortResult<CreateBranchResponse> {
    val result = run(listOf("git", "branch", request.branch, request.baseRevision), repositoryRoot)
    if (result is Execution.Failure) return result.result
    return PortResult.Success(
        CreateBranchResponse(
            branch = request.branch,
            revision = request.baseRevision,
            change = receipt("git-create-branch-${request.branch}", "create-branch"),
        )
    )
  }

  override fun createWorktree(request: CreateWorktreeRequest): PortResult<CreateWorktreeResponse> {
    val result =
        run(listOf("git", "worktree", "add", request.path.value, request.branch), repositoryRoot)
    if (result is Execution.Failure) return result.result
    return PortResult.Success(
        CreateWorktreeResponse(
            workspaceId = request.workspaceId,
            branch = request.branch,
            path = request.path,
            change = receipt("git-create-worktree-${request.workspaceId}", "create-worktree"),
        )
    )
  }

  override fun restack(request: RestackRequest): PortResult<RestackResponse> {
    val path = resolveWorkspace(request.workspaceId) ?: return workspaceFailure(request.workspaceId)
    val current = run(listOf("git", "rev-parse", "HEAD"), path)
    if (current is Execution.Failure) return current.result
    val currentResult = (current as Execution.Success).result
    val currentRevision = currentResult.stdout.trim()
    if (currentRevision != request.expectedRevision) {
      return failure(
          code = "STALE_REVISION",
          message =
              "예상 revision과 현재 revision이 다릅니다: expected=${request.expectedRevision}, current=$currentRevision",
          retryable = true,
          target = request.workspaceId,
      )
    }

    val beforeDiff = run(diffCommand(request.baseRevision), path)
    if (beforeDiff is Execution.Failure) return beforeDiff.result
    val beforeDiffResult = (beforeDiff as Execution.Success).result
    val rebase = run(listOf("git", "rebase", request.baseRevision), path)
    if (rebase is Execution.Failure) return restackFailure(request.workspaceId, rebase.result, path)
    val revision = run(listOf("git", "rev-parse", "HEAD"), path)
    if (revision is Execution.Failure) return revision.result
    val revisionResult = (revision as Execution.Success).result
    val afterDiff = run(diffCommand(request.baseRevision), path)
    if (afterDiff is Execution.Failure) return afterDiff.result
    val afterDiffResult = (afterDiff as Execution.Success).result

    val revisionValue = revisionResult.stdout.trim()
    if (revisionValue.isBlank()) {
      return failure("RESTACK_FAILED", "rebase 이후 revision이 비어 있습니다.", request.workspaceId)
    }
    return PortResult.Success(
        RestackResponse(
            revision = revisionValue,
            fingerprint = fingerprint(revisionValue, afterDiffResult.stdout, ""),
            diffChanged = beforeDiffResult.stdout != afterDiffResult.stdout,
            change =
                ChangeReceipt(
                    id = "git-restack-${request.workspaceId}",
                    operation = "restack",
                    beforeRevision = request.expectedRevision,
                    afterRevision = revisionValue,
                ),
        )
    )
  }

  override fun continueRestack(
      request: ContinueRestackRequest
  ): PortResult<ContinueRestackResponse> {
    val path = resolveWorkspace(request.workspaceId) ?: return workspaceFailure(request.workspaceId)
    val result = run(listOf("git", "-c", "core.editor=true", "rebase", "--continue"), path)
    if (result is Execution.Failure) {
      val conflicts = unresolvedPaths(path)
      return failure(
          code = if (conflicts.isEmpty()) "RESTACK_CONTINUE_FAILED" else "SYNC_CONFLICT",
          message =
              buildString {
                append("git rebase --continue에 실패했습니다")
                if (result.result.error.message.isNotBlank()) {
                  append(": ").append(result.result.error.message)
                }
                if (conflicts.isNotEmpty()) {
                  append("; 충돌 파일: ").append(conflicts.joinToString(", "))
                }
              },
          target = request.workspaceId,
      )
    }
    return PortResult.Success(
        ContinueRestackResponse(
            change =
                ChangeReceipt(
                    id = "git-restack-continue-${request.workspaceId}",
                    operation = "restack-continue",
                )
        )
    )
  }

  override fun abortRestack(request: AbortRestackRequest): PortResult<AbortRestackResponse> {
    val path = resolveWorkspace(request.workspaceId) ?: return workspaceFailure(request.workspaceId)
    val result = run(listOf("git", "rebase", "--abort"), path)
    if (result is Execution.Failure) {
      return failure(
          code = "RESTACK_ABORT_FAILED",
          message = "git rebase --abort에 실패했습니다: ${result.result.error.message}",
          target = request.workspaceId,
      )
    }
    return PortResult.Success(
        AbortRestackResponse(
            change =
                ChangeReceipt(
                    id = "git-restack-abort-${request.workspaceId}",
                    operation = "restack-abort",
                )
        )
    )
  }

  override fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse> {
    if (request.expectedRevision != null) {
      val revision = run(listOf("git", "rev-parse", "refs/heads/${request.branch}"), repositoryRoot)
      if (revision is Execution.Failure) return revision.result
      val currentRevision = (revision as Execution.Success).result.stdout.trim()
      if (currentRevision != request.expectedRevision) {
        return failure(
            code = "STALE_REVISION",
            message =
                "예상 branch revision과 현재 revision이 다릅니다: expected=${request.expectedRevision}, current=$currentRevision",
            retryable = true,
            target = request.branch,
        )
      }
    }
    val result = run(listOf("git", "branch", "-D", request.branch), repositoryRoot)
    if (result is Execution.Failure) return result.result
    return PortResult.Success(
        RemoveBranchResponse(
            request.branch,
            receipt("git-remove-branch-${request.branch}", "remove-branch"),
        )
    )
  }

  private fun findRevision(request: MainRevisionRequest): String? {
    val refs = listOf("refs/remotes/${request.remote}/${request.branch}", "FETCH_HEAD")
    refs.forEach { ref ->
      val result = run(listOf("git", "rev-parse", ref), repositoryRoot)
      if (result is Execution.Success && result.result.stdout.trim().isNotBlank()) {
        return result.result.stdout.trim()
      }
    }
    return null
  }

  private fun resolveWorkspace(workspaceId: WorkspaceId): Path? =
      try {
        workspacePath(workspaceId)
      } catch (_: Exception) {
        null
      }

  private fun restackFailure(
      workspaceId: WorkspaceId,
      rebase: PortResult.Failure,
      path: Path,
  ): PortResult.Failure {
    val conflicts = unresolvedPaths(path)
    val detail = rebase.error.message
    return failure(
        code = if (conflicts.isEmpty()) "RESTACK_FAILED" else "SYNC_CONFLICT",
        message =
            buildString {
              append("git rebase에 실패했습니다")
              if (detail.isNotBlank()) append(": ").append(detail)
              if (conflicts.isNotEmpty()) append("; 충돌 파일: ").append(conflicts.joinToString(", "))
            },
        target = workspaceId,
    )
  }

  private fun unresolvedPaths(path: Path): List<String> =
      run(listOf("git", "diff", "--name-only", "--diff-filter=U"), path).let { result ->
        if (result is Execution.Success) result.result.stdout.lines().filter(String::isNotBlank)
        else emptyList()
      }

  private fun run(command: List<String>, workingDirectory: Path): Execution {
    return try {
      val result = commandRunner.run(command, workingDirectory)
      if (result.exitCode == 0) {
        Execution.Success(result)
      } else {
        Execution.Failure(
            failure(
                code = commandErrorCode(command),
                message = commandMessage(result),
                target = workingDirectory.toString(),
            )
        )
      }
    } catch (failure: Exception) {
      Execution.Failure(
          failure(
              code = commandErrorCode(command),
              message = "명령을 실행할 수 없습니다: ${failure.message ?: failure.javaClass.simpleName}",
              target = workingDirectory.toString(),
          )
      )
    }
  }

  private fun commandErrorCode(command: List<String>): String =
      when {
        command.contains("fetch") -> "MAIN_REVISION_REFRESH_FAILED"
        command.contains("status") || command.contains("rev-parse") -> "GIT_INSPECT_FAILED"
        command.contains("worktree") -> "WORKTREE_CREATE_FAILED"
        command.contains("branch") && command.contains("-D") -> "BRANCH_REMOVE_FAILED"
        command.contains("branch") -> "BRANCH_CREATE_FAILED"
        command.contains("rebase") -> "RESTACK_FAILED"
        else -> "GIT_COMMAND_FAILED"
      }

  private fun commandMessage(result: CommandResult): String =
      result.stderr.trim().ifBlank { result.stdout.trim() }.ifBlank { "종료 코드 ${result.exitCode}" }

  private fun diffCommand(baseRevision: String): List<String> =
      listOf("git", "diff", "--binary", "$baseRevision...HEAD")

  private fun fingerprint(revision: String, diff: String, untrackedHashes: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest("$revision\u0000$diff\u0000$untrackedHashes".toByteArray()).joinToString(
        ""
    ) {
      "%02x".format(it)
    }
  }

  private fun receipt(id: String, operation: String): ChangeReceipt =
      ChangeReceipt(id = id, operation = operation)

  private fun workspaceFailure(workspaceId: WorkspaceId): PortResult.Failure =
      failure("WORKSPACE_NOT_FOUND", "workspace 경로를 확인할 수 없습니다: $workspaceId", workspaceId)

  private fun failure(
      code: String,
      message: String,
      target: String? = null,
      retryable: Boolean = false,
  ): PortResult.Failure = PortResult.Failure(PortError(code, message, retryable, target))

  private sealed interface Execution {
    data class Success(val result: CommandResult) : Execution

    data class Failure(val result: PortResult.Failure) : Execution
  }
}
