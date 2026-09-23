package io.springkit.workflow.adapter.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PublishBranchRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import java.nio.file.Path

class LocalGitPublishAdapterTest :
    FunSpec({
      context("Worktree 변경을 게시하면") {
        test("staged 변경이 있으면, PR 제목으로 commit하고 upstream Branch를 push합니다") {
          val runner = RecordingPublishCommandRunner()
          enqueueInspect(runner, "head-1\n", " M src/Main.kt\n", "diff\n")
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "src/Main.kt\n", ""))
          runner.enqueue(CommandResult(0, "[main commit]\n", ""))
          runner.enqueue(CommandResult(0, "pushed\n", ""))
          runner.enqueue(CommandResult(0, "head-2\n", ""))
          enqueueInspect(runner, "head-2\n", "", "")
          val repositoryRoot = Path.of("/repo")
          val workspace = Path.of("/workspace/sk-101")
          val adapter = LocalGitAdapter(repositoryRoot, { workspace }, runner)

          val result =
              adapter.publish(
                  PublishBranchRequest(
                      workspaceId = "ws-101",
                      branch = "sk-101",
                      commitTitle = "[sk-101] 변경 설명",
                  )
              )

          val response =
              result
                  .shouldBeInstanceOf<PortResult.Success<*>>()
                  .value
                  .shouldBeInstanceOf<io.springkit.workflow.application.PublishBranchResponse>()
          response.committed shouldBe true
          response.revision shouldBe "head-2"
          runner.commands shouldContainExactly
              listOf(
                  PublishInvocation(listOf("git", "rev-parse", "HEAD"), workspace),
                  PublishInvocation(
                      listOf("git", "status", "--porcelain=v1", "--untracked-files=all"),
                      workspace,
                  ),
                  PublishInvocation(listOf("git", "diff", "--binary", "HEAD"), workspace),
                  PublishInvocation(
                      listOf("git", "ls-files", "--others", "--exclude-standard", "-z"),
                      workspace,
                  ),
                  PublishInvocation(listOf("git", "add", "--all"), workspace),
                  PublishInvocation(listOf("git", "diff", "--cached", "--name-only"), workspace),
                  PublishInvocation(listOf("git", "commit", "-m", "[sk-101] 변경 설명"), workspace),
                  PublishInvocation(
                      listOf("git", "push", "--set-upstream", "origin", "sk-101"),
                      workspace,
                  ),
                  PublishInvocation(listOf("git", "rev-parse", "HEAD"), workspace),
                  PublishInvocation(listOf("git", "rev-parse", "HEAD"), workspace),
                  PublishInvocation(
                      listOf("git", "status", "--porcelain=v1", "--untracked-files=all"),
                      workspace,
                  ),
                  PublishInvocation(listOf("git", "diff", "--binary", "HEAD"), workspace),
                  PublishInvocation(
                      listOf("git", "ls-files", "--others", "--exclude-standard", "-z"),
                      workspace,
                  ),
              )
        }

        test("staged 변경이 없으면, 빈 commit을 만들지 않고 push만 수행합니다") {
          val runner = RecordingPublishCommandRunner()
          enqueueInspect(runner, "head-1\n", "", "")
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(CommandResult(0, "head-1\n", ""))
          runner.enqueue(CommandResult(0, "head-1\n", ""))
          enqueueInspect(runner, "head-1\n", "", "")
          val workspace = Path.of("/workspace/sk-102")
          val adapter = LocalGitAdapter(Path.of("/repo"), { workspace }, runner)

          val result =
              adapter.publish(
                  PublishBranchRequest(
                      workspaceId = "ws-102",
                      branch = "sk-102",
                      commitTitle = "[sk-102] 변경 설명",
                  )
              )

          result
              .shouldBeInstanceOf<PortResult.Success<*>>()
              .value
              .shouldBeInstanceOf<io.springkit.workflow.application.PublishBranchResponse>()
              .committed shouldBe false
          runner.commands.any {
            it.command.firstOrNull() == "git" && it.command.contains("commit")
          } shouldBe false
        }
      }
    })

private data class PublishInvocation(val command: List<String>, val workingDirectory: Path)

private class RecordingPublishCommandRunner : CommandRunner {
  val commands = mutableListOf<PublishInvocation>()
  private val results = ArrayDeque<CommandResult>()

  fun enqueue(result: CommandResult) {
    results.addLast(result)
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += PublishInvocation(command, workingDirectory)
    return results.removeFirst()
  }
}

private fun enqueueInspect(
    runner: RecordingPublishCommandRunner,
    revision: String,
    status: String,
    diff: String,
) {
  runner.enqueue(CommandResult(0, revision, ""))
  runner.enqueue(CommandResult(0, status, ""))
  runner.enqueue(CommandResult(0, diff, ""))
  runner.enqueue(CommandResult(0, "", ""))
}
