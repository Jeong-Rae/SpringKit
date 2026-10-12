package io.springkit.workflow.adapter.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.ListRemoteBranchesRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.RemoveRemoteBranchRequest
import io.springkit.workflow.application.RemoveWorktreeRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Path

class LocalGitCleanupAdapterTest :
    FunSpec({
      context("원격 Branch 목록을 조회하면") {
        test("Git 원격 ref를 읽으면, remote HEAD를 제외한 Branch와 revision을 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  "main-revision\trefs/heads/main\nfeature-revision\trefs/heads/feature/sk-101\n",
                  "",
              )
          )
          val repositoryRoot = Path.of("/repo")
          val adapter = LocalGitAdapter(repositoryRoot, { Path.of("/workspace") }, runner)

          val result = adapter.listRemoteBranches(ListRemoteBranchesRequest("origin"))

          result.shouldBeTypeOf<PortResult.Success<*>>().value shouldBe
              io.springkit.workflow.application.ListRemoteBranchesResponse(
                  listOf(
                      io.springkit.workflow.application.RemoteBranch(
                          remote = "origin",
                          branch = "main",
                          revision = "main-revision",
                      ),
                      io.springkit.workflow.application.RemoteBranch(
                          remote = "origin",
                          branch = "feature/sk-101",
                          revision = "feature-revision",
                      ),
                  )
              )
          runner.commands shouldContainExactly
              listOf(
                  CleanupInvocation(
                      listOf(
                          "git",
                          "ls-remote",
                          "--heads",
                          "origin",
                      ),
                      repositoryRoot,
                  )
              )
        }

        test("Git 원격 ref 조회가 실패하면, REMOTE_BRANCH_LIST_FAILED 오류를 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(128, "", "remote ref를 읽을 수 없습니다"))
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result = adapter.listRemoteBranches(ListRemoteBranchesRequest("upstream"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "REMOTE_BRANCH_LIST_FAILED"
          failure.error.message shouldContain "remote ref를 읽을 수 없습니다"
        }

        test("refs/heads가 아닌 ls-remote 응답이면, 원격 Branch 목록 오류를 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(0, "revision\trefs/tags/release\n", ""))
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result = adapter.listRemoteBranches(ListRemoteBranchesRequest("origin"))

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe
              "REMOTE_BRANCH_LIST_FAILED"
        }
      }

      context("원격 Branch를 제거하면") {
        test("expectedRevision이 현재 revision과 일치하면, 검증 후 Git 원격 삭제를 실행합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(0, "feature-revision\trefs/heads/feature/sk-101\n", ""))
          runner.enqueue(CommandResult(0, "deleted\n", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = LocalGitAdapter(repositoryRoot, { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(
                      remote = "origin",
                      branch = "feature/sk-101",
                      expectedRevision = "feature-revision",
                  )
              )

          val response = result.shouldBeTypeOf<PortResult.Success<*>>().value
          response shouldBe
              io.springkit.workflow.application.RemoveRemoteBranchResponse(
                  remote = "origin",
                  branch = "feature/sk-101",
                  change =
                      io.springkit.workflow.application.ChangeReceipt(
                          id = "git-remove-remote-branch-origin-feature/sk-101",
                          operation = "remove-remote-branch",
                      ),
              )
          runner.commands shouldContainExactly
              listOf(
                  CleanupInvocation(
                      listOf(
                          "git",
                          "ls-remote",
                          "--heads",
                          "origin",
                      ),
                      repositoryRoot,
                  ),
                  CleanupInvocation(
                      listOf(
                          "git",
                          "push",
                          "--force-with-lease=refs/heads/feature/sk-101:feature-revision",
                          "origin",
                          "--delete",
                          "feature/sk-101",
                      ),
                      repositoryRoot,
                  ),
              )
        }

        test("expectedRevision이 현재 revision과 다르면, 원격 삭제 없이 재시도 가능한 오류를 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(0, "actual-revision\trefs/heads/feature/sk-101\n", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = LocalGitAdapter(repositoryRoot, { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(
                      remote = "origin",
                      branch = "feature/sk-101",
                      expectedRevision = "expected-revision",
                  )
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          failure.error.retryable shouldBe true
          runner.commands shouldContainExactly
              listOf(
                  CleanupInvocation(
                      listOf(
                          "git",
                          "ls-remote",
                          "--heads",
                          "origin",
                      ),
                      repositoryRoot,
                  )
              )
        }

        test("expectedRevision 대상 Branch가 없으면, 원격 삭제를 실행하지 않습니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(0, "other-revision\trefs/heads/other\n", ""))
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(
                      remote = "origin",
                      branch = "feature/sk-101",
                      expectedRevision = "expected-revision",
                  )
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "REMOTE_BRANCH_NOT_FOUND"
          runner.commands.size shouldBe 1
        }

        test("Git 원격 Branch 삭제가 실패하면, REMOTE_BRANCH_REMOVE_FAILED 오류를 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(1, "", "remote Branch 삭제가 거부되었습니다"))
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(remote = "origin", branch = "feature/sk-101")
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "REMOTE_BRANCH_REMOVE_FAILED"
          failure.error.message shouldContain "remote Branch 삭제가 거부되었습니다"
        }

        test("remote가 옵션처럼 시작하면, Git 명령을 실행하지 않습니다") {
          val runner = RecordingCleanupCommandRunner()
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(
                      remote = "--upload-pack=evil",
                      branch = "feature/sk-101",
                  )
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }

        test("Branch가 옵션처럼 시작하면, Git 명령을 실행하지 않습니다") {
          val runner = RecordingCleanupCommandRunner()
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result =
              adapter.removeRemoteBranch(
                  RemoveRemoteBranchRequest(remote = "origin", branch = "--delete")
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }
      }

      context("Worktree를 제거하면") {
        test("Worktree 경로를 입력하면, 해당 경로를 Git CLI에 전달하고 변경 영수증을 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val repositoryRoot = Path.of("/repo")
          val path = WorkspacePath("workspaces/sk-101")
          val adapter = LocalGitAdapter(repositoryRoot, { Path.of("/workspace") }, runner)

          val result = adapter.removeWorktree(RemoveWorktreeRequest("workspace-101", path))

          val response = result.shouldBeTypeOf<PortResult.Success<*>>().value
          response shouldBe
              io.springkit.workflow.application.RemoveWorktreeResponse(
                  workspaceId = "workspace-101",
                  path = path,
                  change =
                      io.springkit.workflow.application.ChangeReceipt(
                          id = "git-remove-worktree-workspace-101",
                          operation = "remove-worktree",
                      ),
              )
          runner.commands shouldContainExactly
              listOf(
                  CleanupInvocation(
                      listOf("git", "worktree", "remove", "workspaces/sk-101"),
                      repositoryRoot,
                  )
              )
        }

        test("Git Worktree 삭제가 실패하면, WORKTREE_REMOVE_FAILED 오류를 반환합니다") {
          val runner = RecordingCleanupCommandRunner()
          runner.enqueue(CommandResult(1, "", "Worktree가 변경되어 있습니다"))
          val adapter = LocalGitAdapter(Path.of("/repo"), { Path.of("/workspace") }, runner)

          val result =
              adapter.removeWorktree(
                  RemoveWorktreeRequest("workspace-101", WorkspacePath("workspaces/sk-101"))
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "WORKTREE_REMOVE_FAILED"
          failure.error.message shouldContain "Worktree가 변경되어 있습니다"
        }
      }
    })

private data class CleanupInvocation(val command: List<String>, val workingDirectory: Path)

private class RecordingCleanupCommandRunner : CommandRunner {
  val commands = mutableListOf<CleanupInvocation>()
  private val results = ArrayDeque<CommandResult>()

  fun enqueue(result: CommandResult) {
    results.addLast(result)
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += CleanupInvocation(command, workingDirectory)
    return results.removeFirst()
  }
}
