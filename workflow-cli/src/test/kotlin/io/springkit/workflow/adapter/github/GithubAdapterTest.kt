package io.springkit.workflow.adapter.github

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.AddReviewCommentRequest
import io.springkit.workflow.application.AddReviewCommentResponse
import io.springkit.workflow.application.GetCiRequest
import io.springkit.workflow.application.GetCiResponse
import io.springkit.workflow.application.GetReviewRequest
import io.springkit.workflow.application.GetReviewResponse
import io.springkit.workflow.application.OpenReviewRequest
import io.springkit.workflow.application.OpenReviewResponse
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StartCiRequest
import io.springkit.workflow.application.StartCiResponse
import io.springkit.workflow.application.UpdateReviewRequest
import io.springkit.workflow.application.UpdateReviewResponse
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.Risk
import java.nio.file.Path

class GithubAdapterTest :
    FunSpec({
      context("GitHub pull request를 열면") {
        test("gh pr create와 gh pr view에 요청 토큰을 전달하면, 도메인 revision을 보존합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "https://github.com/example/repo/pull/17\n", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":true,"baseRefName":"main","headRefName":"sk-27","headRefOid":"abc123"}
                  """
                      .trimIndent(),
                  "",
              )
          )
          val reviewRevision = ReviewRevision("rv-1", 1, "설명")
          val changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1"))
          val adapter = GithubReviewAdapter(Path.of("/repo"), runner)

          val result =
              adapter.open(
                  OpenReviewRequest(
                      subTaskId = "sk-27",
                      title = "기능 추가",
                      body = "설명",
                      base = "main",
                      branch = "sk-27",
                      risk = Risk.HIGH,
                      exposure = Exposure.UNCHANGED,
                      changeRevision = changeRevision,
                      reviewRevision = reviewRevision,
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<OpenReviewResponse>()
          response.pullRequest.changeRevision shouldBe changeRevision
          response.pullRequest.reviewRevision shouldBe reviewRevision
          runner.commands shouldContainExactly
              listOf(
                  Invocation(
                      listOf(
                          "gh",
                          "pr",
                          "create",
                          "--draft",
                          "--title",
                          "기능 추가",
                          "--body",
                          "설명",
                          "--base",
                          "main",
                          "--head",
                          "sk-27",
                      ),
                      Path.of("/repo"),
                  ),
                  Invocation(
                      listOf(
                          "gh",
                          "pr",
                          "view",
                          "17",
                          "--json",
                          "number,title,body,state,isDraft,baseRefName,headRefName,headRefOid,reviewDecision,author,comments,reviews",
                      ),
                      Path.of("/repo"),
                  ),
              )
        }
      }

      context("GitHub pull request를 조회하면") {
        test("GitHub JSON을 kotlinx.serialization으로 변환하면, pull request 상태와 revision을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"abc123","reviewDecision":"APPROVED"}
                  """
                      .trimIndent(),
                  "",
              )
          )
          val result = GithubReviewAdapter(Path.of("/repo"), runner).get(GetReviewRequest("17"))

          val pullRequest =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          pullRequest.id shouldBe "17"
          pullRequest.subTaskId shouldBe "sk-27"
          pullRequest.state.name shouldBe "APPROVED"
          pullRequest.changeRevision.diff.identity shouldBe "abc123"
        }

        test("persisted diff identity와 GitHub head OID가 다르면, 새 change revision을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-2"}""",
                  "",
              )
          )
          val result =
              GithubReviewAdapter(
                      Path.of("/repo"),
                      runner,
                      currentPullRequest = { pullRequest() },
                  )
                  .get(GetReviewRequest("17"))

          val current =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          current.changeRevision.number shouldBe 2
          current.changeRevision.id shouldBe "github-change-17-2"
          current.changeRevision.diff.identity shouldBe "diff-2"
          current.ci.name shouldBe "PENDING"
        }
      }

      context("Review 코멘트를 추가하면") {
        test("gh pr comment에 본문 토큰을 전달하면, 새로운 thread를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "https://github.com/example/repo/pull/17\n", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27"}""",
                  "",
              )
          )
          val comment = ReviewComment("comment-1", Actor("human-1", ActorKind.HUMAN), "확인했습니다")
          val request =
              AddReviewCommentRequest("17", "rv-1", comment.author, ReviewLevel.C, comment)

          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, nowEpochMillis = { 0 }).comment(request)

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<AddReviewCommentResponse>()
          response.threadId shouldBe "comment-1"
          response.reviewRevision.threads.single().comments.single() shouldBe comment
          runner.commands.first().tokens shouldBe
              listOf("gh", "pr", "comment", "17", "--body", "[C] 확인했습니다")
          response.reviewRevision.id shouldBe "github-review-17-2-0"
        }

        test("코드 줄과 수준을 지정하면, 현재 diff identity로 리뷰 코멘트를 생성합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          val current = pullRequest()
          val comment =
              ReviewComment(
                  "comment-1",
                  Actor("agent-1", ActorKind.AGENT),
                  "[Agent] 수정이 필요합니다",
                  path = "src/Main.kt",
                  line = 12,
              )
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { current },
                  nowEpochMillis = { 1_000 },
              )

          adapter.comment(
              AddReviewCommentRequest("17", "rv-1", comment.author, ReviewLevel.R, comment)
          )

          runner.commands.first().tokens shouldBe
              listOf(
                  "gh",
                  "api",
                  "--method",
                  "POST",
                  "repos/{owner}/{repo}/pulls/17/comments",
                  "-f",
                  "body=[Agent] [R] 수정이 필요합니다",
                  "-f",
                  "commit_id=diff-1",
                  "-f",
                  "path=src/Main.kt",
                  "-F",
                  "line=12",
                  "-f",
                  "side=RIGHT",
              )
        }

        test("기존 수준 표식이 있으면, 중복 없이 요청한 수준으로 교체합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27"}""",
                  "",
              )
          )
          val comment =
              ReviewComment(
                  "comment-2",
                  Actor("agent-1", ActorKind.AGENT),
                  "[Agent] [R] 다시 확인해 주세요",
              )

          GithubReviewAdapter(Path.of("/repo"), runner, nowEpochMillis = { 2_000 })
              .comment(
                  AddReviewCommentRequest("17", "rv-1", comment.author, ReviewLevel.C, comment)
              )

          runner.commands.first().tokens shouldBe
              listOf("gh", "pr", "comment", "17", "--body", "[Agent] [C] 다시 확인해 주세요")
        }
      }

      context("GitHub pull request를 갱신하면") {
        test("Stack 기준 Branch가 바뀌면, gh pr edit에 새 base를 전달합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { pullRequest().copy(base = "sk-26") },
              )

          val result =
              adapter.update(
                  UpdateReviewRequest(
                      pullRequestId = "17",
                      expectedReviewRevisionId = "rv-1",
                      base = "main",
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<UpdateReviewResponse>()
          response.pullRequest.base shouldBe "main"
          runner.commands.first().tokens shouldBe listOf("gh", "pr", "edit", "17", "--base", "main")
        }

        test("애플리케이션이 revision을 발급하면, GitHub 조회 뒤 해당 revision을 보존합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-2"}""",
                  "",
              )
          )
          val before = pullRequest()
          val nextReview = ReviewRevision("rv-2", 2, "설명")
          val nextChange = ChangeRevision("cr-2", 2, Diff("diff-2"))
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { before },
              )

          val result =
              adapter.update(
                  UpdateReviewRequest(
                      pullRequestId = "17",
                      expectedReviewRevisionId = "rv-1",
                      changeRevision = nextChange,
                      reviewRevision = nextReview,
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<UpdateReviewResponse>()
          response.pullRequest.reviewRevision shouldBe nextReview
          response.pullRequest.changeRevision shouldBe nextChange
          response.codeChanged shouldBe true
        }
      }

      context("gh 명령이 실패하면") {
        test("종료 코드가 0이 아니면, 종료 코드와 stderr를 PortError로 변환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(1, "", "pull request not found"))

          val result = GithubReviewAdapter(Path.of("/repo"), runner).get(GetReviewRequest("404"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_COMMAND_FAILED"
          failure.error.message shouldBe "pull request not found"
          runner.commands.size shouldBe 1
        }

        test("gh 프로세스를 시작할 수 없으면, 재시도 가능한 PortError를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueueFailure(IllegalStateException("gh를 찾을 수 없습니다"))

          val result = GithubReviewAdapter(Path.of("/repo"), runner).get(GetReviewRequest("17"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_COMMAND_FAILED"
          failure.error.retryable shouldBe true
        }

        test("revision이 오래되면, gh 명령을 실행하지 않고 거부합니다") {
          val runner = RecordingCommandRunner()
          val current = pullRequest()
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { current },
              )

          val result =
              adapter.update(
                  UpdateReviewRequest(
                      pullRequestId = "17",
                      expectedReviewRevisionId = "rv-0",
                      body = "새 설명",
                  )
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          runner.commands shouldBe emptyList()
        }
      }

      context("GitHub CI 상태를 조회하면") {
        test("statusCheckRollup이 성공 또는 실패 상태이면, CiRun과 CiStatus로 변환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {"headRefOid":"abc123","statusCheckRollup":[{"name":"build","status":"COMPLETED","conclusion":"SUCCESS","databaseId":1},{"name":"test","status":"COMPLETED","conclusion":"FAILURE","databaseId":2}]}
                  """
                      .trimIndent(),
                  "",
              )
          )

          val result = GithubCiAdapter(Path.of("/repo"), runner).get(GetCiRequest("17", "abc123"))

          val response =
              result.shouldBeTypeOf<PortResult.Success<*>>().value.shouldBeTypeOf<GetCiResponse>()
          response.status.name shouldBe "FAILED"
          response.runs.map { it.id } shouldBe listOf("1", "2")
          runner.commands.single().tokens shouldBe
              listOf("gh", "pr", "view", "17", "--json", "headRefOid,statusCheckRollup")
        }

        test("CI를 시작하면, statusCheckRollup 조회와 변경 영수증을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(0, "{\"headRefOid\":\"abc123\",\"statusCheckRollup\":[]}", "")
          )

          val result =
              GithubCiAdapter(Path.of("/repo"), runner).start(StartCiRequest("17", "abc123"))

          val response =
              result.shouldBeTypeOf<PortResult.Success<*>>().value.shouldBeTypeOf<StartCiResponse>()
          response.run.status.name shouldBe "PENDING"
          response.change.operation shouldBe "github-ci-start"
        }

        test("CI 조회용 gh 프로세스를 시작할 수 없으면, 재시도 가능한 실패를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueueFailure(IllegalStateException("gh를 찾을 수 없습니다"))

          val result = GithubCiAdapter(Path.of("/repo"), runner).get(GetCiRequest("17"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_CI_FAILED"
          failure.error.retryable shouldBe true
        }
      }
    })

private fun pullRequest(): PullRequest =
    PullRequest(
        id = "17",
        subTaskId = "sk-27",
        title = "기능 추가",
        body = "설명",
        base = "main",
        state = PullRequestState.REVIEW,
        reviewRevision = ReviewRevision("rv-1", 1, "설명"),
        changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
    )

private data class Invocation(val tokens: List<String>, val workingDirectory: Path)

private class RecordingCommandRunner : CommandRunner {
  private val results = ArrayDeque<() -> CommandResult>()
  val commands = mutableListOf<Invocation>()

  fun enqueue(result: CommandResult) {
    results.addLast { result }
  }

  fun enqueueFailure(failure: RuntimeException) {
    results.addLast { throw failure }
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += Invocation(command, workingDirectory)
    return results.removeFirst().invoke()
  }
}
