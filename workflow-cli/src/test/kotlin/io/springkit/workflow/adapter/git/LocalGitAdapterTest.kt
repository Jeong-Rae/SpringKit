package io.springkit.workflow.adapter.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.AbortRestackRequest
import io.springkit.workflow.application.CheckRemotePushRequest
import io.springkit.workflow.application.ContinueRestackRequest
import io.springkit.workflow.application.CreateBranchRequest
import io.springkit.workflow.application.CreateWorktreeRequest
import io.springkit.workflow.application.GitInspectRequest
import io.springkit.workflow.application.MainRevisionRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PublishBranchRequest
import io.springkit.workflow.application.RemoveBranchRequest
import io.springkit.workflow.application.RemoveWorktreeRequest
import io.springkit.workflow.application.RestackRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Files
import java.nio.file.Path

class LocalGitAdapterTest :
    FunSpec({
      context("로컬 Git 명령을 실행하면") {
        test("유효하지 않은 Git 옵션을 실행하면, stdout와 stderr와 종료 코드를 분리합니다") {
          val result = LocalCommandRunner().run(listOf("git", "--definitely-invalid"), Path.of("."))

          result.exitCode shouldBe 129
          result.stdout shouldBe ""
          result.stderr shouldContain "unknown option"
        }
      }

      context("workspace를 조회하면") {
        test("workspaceId로 경로를 찾으면, 해당 경로의 revision과 변경 상태를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "revision-1\n", ""))
          runner.enqueue(CommandResult(0, " M src/Main.kt\n?? notes.md\n", ""))
          runner.enqueue(CommandResult(0, "diff-content\n", ""))
          runner.enqueue(CommandResult(0, "notes.md\u0000", ""))
          runner.enqueue(CommandResult(0, "hash-notes\n", ""))
          val path = Path.of("/tmp/workspace/sk-101")
          val adapter = adapter(runner) { path }

          val result = adapter.inspect(GitInspectRequest("workspace-1"))

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.GitInspectResponse>()
          response shouldBe
              io.springkit.workflow.application.GitInspectResponse(
                  io.springkit.workflow.application.GitStatus(
                      revision = "revision-1",
                      fingerprint = response.status.fingerprint,
                      dirty = true,
                  )
              )
          runner.commands shouldContainExactly
              listOf(
                  Invocation(listOf("git", "rev-parse", "HEAD"), path),
                  Invocation(
                      listOf("git", "status", "--porcelain=v1", "--untracked-files=all"),
                      path,
                  ),
                  Invocation(listOf("git", "diff", "--binary", "HEAD"), path),
                  Invocation(
                      listOf("git", "ls-files", "--others", "--exclude-standard", "-z"),
                      path,
                  ),
                  Invocation(listOf("git", "hash-object", "--", "notes.md"), path),
              )
        }

        test("같은 파일의 내용이 달라지면, fingerprint도 달라집니다") {
          val firstRunner = RecordingCommandRunner()
          enqueueInspect(firstRunner, "diff-a\n", "")
          val secondRunner = RecordingCommandRunner()
          enqueueInspect(secondRunner, "diff-b\n", "")
          val first = adapter(firstRunner) { Path.of("/workspace/sk-101") }
          val second = adapter(secondRunner) { Path.of("/workspace/sk-101") }

          val firstStatus = first.inspect(GitInspectRequest("workspace-1"))
          val secondStatus = second.inspect(GitInspectRequest("workspace-1"))

          firstStatus
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.GitInspectResponse>()
              .status
              .fingerprint shouldNotBe
              secondStatus
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.GitInspectResponse>()
                  .status
                  .fingerprint
        }

        test("untracked 파일의 내용이 달라지면, fingerprint도 달라집니다") {
          val firstRunner = RecordingCommandRunner()
          enqueueInspect(firstRunner, "same-diff\n", "hash-a\n")
          val secondRunner = RecordingCommandRunner()
          enqueueInspect(secondRunner, "same-diff\n", "hash-b\n")
          val first = adapter(firstRunner) { Path.of("/workspace/sk-101") }
          val second = adapter(secondRunner) { Path.of("/workspace/sk-101") }

          val firstStatus = first.inspect(GitInspectRequest("workspace-1"))
          val secondStatus = second.inspect(GitInspectRequest("workspace-1"))

          firstStatus
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.GitInspectResponse>()
              .status
              .fingerprint shouldNotBe
              secondStatus
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.GitInspectResponse>()
                  .status
                  .fingerprint
        }
      }

      context("원격 게시 가능성을 확인하면") {
        test("대상 ref의 dry-run 상태를 확인하고 원격 변경을 보내지 않습니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "=\tHEAD:refs/heads/sk-101\t[up to date]\n", ""))
          val workspace = Path.of("/workspace/sk-101")
          val adapter = adapter(runner) { workspace }

          val result = adapter.checkRemotePush(CheckRemotePushRequest("ws-101", "sk-101"))

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.CheckRemotePushResponse>()
              .publishable shouldBe true
          runner.commands shouldBe
              listOf(
                  Invocation(
                      listOf(
                          "git",
                          "push",
                          "--dry-run",
                          "--porcelain",
                          "--no-verify",
                          "origin",
                          "HEAD:refs/heads/sk-101",
                      ),
                      workspace,
                  )
              )
        }

        test("porcelain이 대상 ref를 rejected로 표시하면 게시 불가를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(0, "!\tHEAD:refs/heads/sk-101\t[rejected] non-fast-forward\n", "")
          )
          val adapter = adapter(runner) { Path.of("/workspace/sk-101") }

          val response =
              adapter
                  .checkRemotePush(CheckRemotePushRequest("ws-101", "sk-101"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CheckRemotePushResponse>()

          response.publishable shouldBe false
          response.message shouldNotBe null
        }

        test("원격 dry-run 명령이 실패하면 게시 불가와 원인을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(1, "", "permission denied"))
          val adapter = adapter(runner) { Path.of("/workspace/sk-101") }

          val response =
              adapter
                  .checkRemotePush(CheckRemotePushRequest("ws-101", "sk-101"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CheckRemotePushResponse>()

          response.publishable shouldBe false
          response.message.orEmpty() shouldContain "permission denied"
        }

        test("다른 원격 ref만 응답하면 대상 Branch의 게시 가능성을 확인할 수 없습니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "=\tHEAD:refs/heads/sk-other\t[up to date]\n", ""))
          val adapter = adapter(runner) { Path.of("/workspace/sk-101") }

          val response =
              adapter
                  .checkRemotePush(CheckRemotePushRequest("ws-101", "sk-101"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CheckRemotePushResponse>()

          response.publishable shouldBe false
          response.message shouldNotBe null
        }

        test("잘못된 branch 입력은 Git 명령 없이 거절합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace/sk-101") }

          val result = adapter.checkRemotePush(CheckRemotePushRequest("ws-101", "--delete"))

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }
      }

      context("main revision을 갱신하면") {
        test("원격 main fetch가 성공하면, 원격 ref의 revision을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "revision-2\n", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result = adapter.refreshMain(MainRevisionRequest("origin", "main"))

          result.shouldBeTypeOf<PortResult.Success<*>>().value shouldBe
              io.springkit.workflow.application.MainRevisionResponse("revision-2")
          runner.commands shouldContainExactly
              listOf(
                  Invocation(listOf("git", "fetch", "origin", "main"), repositoryRoot),
                  Invocation(
                      listOf("git", "rev-parse", "refs/remotes/origin/main"),
                      repositoryRoot,
                  ),
              )
        }

        test("원격 main fetch가 실패하면, 로컬 ref를 사용하지 않고 실패를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(1, "", "network unavailable"))
          val repositoryRoot = Path.of("/repo")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result = adapter.refreshMain(MainRevisionRequest("origin", "main"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "MAIN_REVISION_REFRESH_FAILED"
          runner.commands shouldContainExactly
              listOf(Invocation(listOf("git", "fetch", "origin", "main"), repositoryRoot))
        }

        test("remote가 Git 옵션이면, fetch 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace") }

          val result = adapter.refreshMain(MainRevisionRequest("--upload-pack=evil", "main"))

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }

        test("branch가 Git 옵션이면, fetch 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace") }

          val result = adapter.refreshMain(MainRevisionRequest("origin", "--delete"))

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }
      }

      context("branch를 생성하면") {
        test("base revision에서 branch를 생성하면, Git 명령과 변경 영수증을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result =
              adapter.createBranch(CreateBranchRequest("feature/sk-101", "main", "revision-3"))

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CreateBranchResponse>()
          response.branch shouldBe "feature/sk-101"
          response.revision shouldBe "revision-3"
          response.change.operation shouldBe "create-branch"
          runner.commands shouldContainExactly
              listOf(
                  Invocation(
                      listOf("git", "branch", "feature/sk-101", "revision-3"),
                      repositoryRoot,
                  )
              )
        }

        test("branch와 기준 revision이 Git 옵션이면, 명령 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace") }

          val invalidBranch =
              adapter.createBranch(CreateBranchRequest("--force", "main", "revision-3"))
          val invalidRevision =
              adapter.createBranch(CreateBranchRequest("feature/sk-101", "main", "--help"))

          invalidBranch.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          invalidRevision.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe
              "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }
      }

      context("Git ref 입력을 검증하면") {
        test("worktree branch가 Git 옵션이면, 명령 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest("workspace-2", "--detach", WorkspacePath("workspaces/sk-2"))
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }

        test("restack 기준 revision이 Git 옵션이면, workspace 조회 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { error("workspace가 조회되면 안 됩니다") }

          val result =
              adapter.restack(
                  RestackRequest("workspace-2", "feature/sk-2", "main", "--onto=evil", "head")
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }

        test("제거할 branch와 expected revision이 Git 옵션이면, 명령 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { Path.of("/workspace") }

          val invalidBranch = adapter.removeBranch(RemoveBranchRequest("--force"))
          val invalidRevision = adapter.removeBranch(RemoveBranchRequest("feature/sk-2", "--help"))

          invalidBranch.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          invalidRevision.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe
              "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }

        test("publish remote와 branch가 Git 옵션이면, workspace 조회 전에 입력을 거부합니다") {
          val runner = RecordingCommandRunner()
          val adapter = adapter(runner) { error("workspace가 조회되면 안 됩니다") }

          val invalidRemote =
              adapter.publish(
                  PublishBranchRequest(
                      workspaceId = "workspace-2",
                      branch = "feature/sk-2",
                      commitTitle = "변경",
                      remote = "--receive-pack=evil",
                  )
              )
          val invalidBranch =
              adapter.publish(
                  PublishBranchRequest(
                      workspaceId = "workspace-2",
                      branch = "--force",
                      commitTitle = "변경",
                  )
              )

          invalidRemote.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          invalidBranch.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "INVALID_ARGUMENT"
          runner.commands shouldBe emptyList()
        }
      }

      context("worktree를 생성하면") {
        test("worktree 생성을 요청하면, 경로와 branch를 Git 명령 토큰으로 전달합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val repositoryRoot = Files.createTempDirectory("local-git-adapter")
          val requestedPath = WorkspacePath("workspaces/sk-102")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest("workspace-2", "feature/sk-102", requestedPath)
              )

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.CreateWorktreeResponse>()
              .path shouldBe requestedPath
          runner.commands shouldContainExactly
              listOf(
                  Invocation(
                      listOf("git", "worktree", "add", "workspaces/sk-102", "feature/sk-102"),
                      repositoryRoot,
                  )
              )
        }

        test("worktree 경로가 사라지면, WORKSPACE_NOT_FOUND 오류를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(128, "", "fatal: not a git repository"))
          val adapter = adapter(runner) { Path.of("/tmp/workspace/sk-101") }

          val result = adapter.inspect(GitInspectRequest("workspace-1"))

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_NOT_FOUND"
        }
      }

      test("관리 root가 주어지면, 검증한 절대 경로를 Git 명령에 전달합니다") {
        val runner = RecordingCommandRunner()
        runner.enqueue(CommandResult(0, "", ""))
        val root = Files.createTempDirectory("local-git-root-")
        val requestedPath = WorkspacePath("workspaces/sk-102")
        val adapter = adapter(runner, root, root) { Path.of("/workspace") }

        adapter.createWorktree(
            CreateWorktreeRequest("workspace-2", "feature/sk-102", requestedPath)
        )

        runner.commands shouldBe
            listOf(
                Invocation(
                    listOf(
                        "git",
                        "worktree",
                        "add",
                        root.resolve(requestedPath.value).toAbsolutePath().normalize().toString(),
                        "feature/sk-102",
                    ),
                    root,
                )
            )
      }

      context("managed root 경계를 검사하면") {
        test("절대 경로가 root 밖이면, worktree 생성을 거부합니다") {
          val runner = RecordingCommandRunner()
          val root = Files.createTempDirectory("local-git-root-")
          val outside = Files.createTempDirectory("local-git-outside-")
          val adapter = adapter(runner, root, root) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest(
                      "workspace-2",
                      "feature/sk-102",
                      WorkspacePath(outside.toString()),
                  )
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_PATH_INVALID"
          runner.commands shouldBe emptyList()
        }

        test("`..`으로 root 밖으로 탈출하면, worktree 생성을 거부합니다") {
          val runner = RecordingCommandRunner()
          val root = Files.createTempDirectory("local-git-root-")
          val adapter = adapter(runner, root, root) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest(
                      "workspace-2",
                      "feature/sk-102",
                      WorkspacePath("../outside"),
                  )
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_PATH_INVALID"
          runner.commands shouldBe emptyList()
        }

        test("외부를 가리키는 심볼릭 링크이면, worktree 생성을 거부합니다") {
          val runner = RecordingCommandRunner()
          val root = Files.createTempDirectory("local-git-root-")
          val outside = Files.createTempDirectory("local-git-outside-")
          Files.createSymbolicLink(root.resolve("link"), outside)
          val adapter = adapter(runner, root, root) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest("workspace-2", "feature/sk-102", WorkspacePath("link"))
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_PATH_INVALID"
          runner.commands shouldBe emptyList()
        }

        test("외부 symlink 아래의 미존재 자식 경로이면, worktree 생성을 거부합니다") {
          val runner = RecordingCommandRunner()
          val root = Files.createTempDirectory("local-git-root-")
          val outside = Files.createTempDirectory("local-git-outside-")
          Files.createSymbolicLink(root.resolve("link"), outside)
          val adapter = adapter(runner, root, root) { Path.of("/workspace") }

          val result =
              adapter.createWorktree(
                  CreateWorktreeRequest(
                      "workspace-2",
                      "feature/sk-102",
                      WorkspacePath("link/nonexistent"),
                  )
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_PATH_INVALID"
          runner.commands shouldBe emptyList()
        }

        test("root 밖 경로이면, worktree 제거를 Git 명령 전에 거부합니다") {
          val runner = RecordingCommandRunner()
          val root = Files.createTempDirectory("local-git-root-")
          val adapter = adapter(runner, root, root) { Path.of("/workspace") }

          val result =
              adapter.removeWorktree(
                  RemoveWorktreeRequest("workspace-2", WorkspacePath("../outside"))
              )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe "WORKSPACE_PATH_INVALID"
          runner.commands shouldBe emptyList()
        }
      }

      context("현재 revision이 예상 revision과 다르면") {
        test("현재 revision이 예상 revision과 다르면, restack 없이 재시도 가능한 오류를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "actual\n", ""))
          val workspace = Path.of("/workspace/sk-103")
          val adapter = adapter(runner) { workspace }

          val result =
              adapter.restack(
                  RestackRequest("workspace-3", "feature/sk-103", "main", "base", "expected")
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          failure.error.retryable shouldBe true
          runner.commands shouldBe listOf(Invocation(listOf("git", "rev-parse", "HEAD"), workspace))
        }
      }

      context("restack 중 충돌이 발생하면") {
        test("restack 중 충돌이 발생하면, unmerged 파일 목록이 있는 오류를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "expected\n", ""))
          runner.enqueue(CommandResult(0, "before\n", ""))
          runner.enqueue(CommandResult(1, "", "CONFLICT (content): conflict\n"))
          runner.enqueue(CommandResult(0, "src/Main.kt\n", ""))
          val workspace = Path.of("/workspace/sk-104")
          val adapter = adapter(runner) { workspace }

          val result =
              adapter.restack(
                  RestackRequest("workspace-4", "feature/sk-104", "main", "base", "expected")
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "SYNC_CONFLICT"
          failure.error.message shouldContain "src/Main.kt"
          runner.commands.last() shouldBe
              Invocation(listOf("git", "diff", "--name-only", "--diff-filter=U"), workspace)
        }
      }

      context("충돌 복구를 요청하면") {
        test("동기화 계속을 요청하면, git rebase --continue를 workspace에서 실행합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val workspace = Path.of("/workspace/sk-107")
          val adapter = adapter(runner) { workspace }

          val result = adapter.continueRestack(ContinueRestackRequest("workspace-7"))

          result.shouldBeTypeOf<PortResult.Success<*>>()
          runner.commands shouldBe
              listOf(
                  Invocation(
                      listOf("git", "-c", "core.editor=true", "rebase", "--continue"),
                      workspace,
                  )
              )
        }

        test("동기화 취소를 요청하면, git rebase --abort를 workspace에서 실행합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val workspace = Path.of("/workspace/sk-108")
          val adapter = adapter(runner) { workspace }

          val result = adapter.abortRestack(AbortRestackRequest("workspace-8"))

          result.shouldBeTypeOf<PortResult.Success<*>>()
          runner.commands shouldBe listOf(Invocation(listOf("git", "rebase", "--abort"), workspace))
        }
      }

      context("expectedRevision이 일치하는 상태에서 restack하면") {
        test("expectedRevision이 일치하면, rebase 이후 revision과 diff 변경 여부를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "expected\n", ""))
          runner.enqueue(CommandResult(0, "same-diff\n", ""))
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "after\n", ""))
          runner.enqueue(CommandResult(0, "changed-diff\n", ""))
          val workspace = Path.of("/workspace/sk-105")
          val adapter = adapter(runner) { workspace }

          val result =
              adapter.restack(
                  RestackRequest("workspace-5", "feature/sk-105", "main", "base", "expected")
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.RestackResponse>()
          response.revision shouldBe "after"
          response.diffChanged shouldBe true
          response.change.beforeRevision shouldBe "expected"
          response.change.afterRevision shouldBe "after"
          runner.commands[2] shouldBe Invocation(listOf("git", "rebase", "base"), workspace)
        }
      }

      context("expectedRevision과 함께 branch를 제거하면") {
        test("expectedRevision과 함께 branch 제거를 요청하면, revision을 검증한 뒤 제거합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "revision-6\n", ""))
          runner.enqueue(CommandResult(0, "", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result = adapter.removeBranch(RemoveBranchRequest("feature/sk-106", "revision-6"))

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.RemoveBranchResponse>()
              .branch shouldBe "feature/sk-106"
          runner.commands shouldContainExactly
              listOf(
                  Invocation(
                      listOf("git", "rev-parse", "refs/heads/feature/sk-106"),
                      repositoryRoot,
                  ),
                  Invocation(listOf("git", "branch", "-D", "feature/sk-106"), repositoryRoot),
              )
        }

        test("expectedRevision 대상 branch가 이미 없으면, 삭제 완료로 처리합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(128, "", "fatal: bad revision"))
          runner.enqueue(CommandResult(1, "", ""))
          val repositoryRoot = Path.of("/repo")
          val adapter = adapter(runner, repositoryRoot) { Path.of("/workspace") }

          val result = adapter.removeBranch(RemoveBranchRequest("feature/sk-106", "revision-6"))

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<io.springkit.workflow.application.RemoveBranchResponse>()
              .branch shouldBe "feature/sk-106"
          runner.commands shouldContainExactly
              listOf(
                  Invocation(
                      listOf("git", "rev-parse", "refs/heads/feature/sk-106"),
                      repositoryRoot,
                  ),
                  Invocation(
                      listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/feature/sk-106"),
                      repositoryRoot,
                  ),
              )
        }
      }
    })

private fun adapter(
    runner: RecordingCommandRunner,
    repositoryRoot: Path = Path.of("/repo"),
    managedWorkspaceRoot: Path? = null,
    workspacePath: (String) -> Path,
): LocalGitAdapter = LocalGitAdapter(repositoryRoot, workspacePath, runner, managedWorkspaceRoot)

private data class Invocation(val command: List<String>, val workingDirectory: Path)

private class RecordingCommandRunner : CommandRunner {
  val commands = mutableListOf<Invocation>()
  private val results = ArrayDeque<CommandResult>()

  fun enqueue(result: CommandResult) {
    results.addLast(result)
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += Invocation(command, workingDirectory)
    return results.removeFirst()
  }
}

private fun enqueueInspect(runner: RecordingCommandRunner, diff: String, hash: String) {
  runner.enqueue(CommandResult(0, "revision-1\n", ""))
  runner.enqueue(CommandResult(0, " M src/Main.kt\n?? notes.md\n", ""))
  runner.enqueue(CommandResult(0, diff, ""))
  runner.enqueue(CommandResult(0, "notes.md\u0000", ""))
  runner.enqueue(CommandResult(0, hash, ""))
}
