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
import io.springkit.workflow.application.ReplyReviewThreadRequest
import io.springkit.workflow.application.ResolveReviewThreadRequest
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
import io.springkit.workflow.domain.ReviewThread
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
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_external",
                      commentId = "PRRC_external",
                      databaseId = 201,
                      body = "[R] 외부에서 확인이 필요합니다",
                      path = "src/Main.kt",
                      line = 8,
                  ),
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
          pullRequest.reviewRevision.threads.single().id shouldBe "PRRT_external"
          pullRequest.reviewRevision.threads.single().comments.single().id shouldBe "PRRC_external"
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
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
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

        test("GitHub review thread가 바뀌면, 새 review revision을 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_external",
                      commentId = "PRRC_external",
                      databaseId = 201,
                      body = "[R] 외부에서 확인이 필요합니다",
                      path = "src/Main.kt",
                      line = 8,
                  ),
                  "",
              )
          )
          val previous =
              pullRequest()
                  .copy(
                      reviewRevision =
                          ReviewRevision(
                              "rv-1",
                              1,
                              "설명",
                              threads =
                                  listOf(
                                      ReviewThread(
                                          "PRRT_previous",
                                          ReviewLevel.R,
                                          listOf(
                                              ReviewComment(
                                                  "PRRC_previous",
                                                  Actor("human-1", ActorKind.HUMAN),
                                                  "[R] 이전 의견",
                                                  path = "src/Main.kt",
                                                  line = 8,
                                              )
                                          ),
                                      )
                                  ),
                          )
                  )

          val result =
              GithubReviewAdapter(
                      Path.of("/repo"),
                      runner,
                      currentPullRequest = { previous },
                  )
                  .get(GetReviewRequest("17"))

          val current =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          current.reviewRevision.id shouldBe "github-review-17-2"
          current.reviewRevision.number shouldBe 2
          current.reviewRevision.threads.single().id shouldBe "PRRT_external"
        }

        test("동일한 GitHub review thread를 다시 조회하면, review revision을 유지합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_external",
                      commentId = "PRRC_external",
                      databaseId = 201,
                      body = "[R] 외부에서 확인이 필요합니다",
                      path = "src/Main.kt",
                      line = 8,
                  ),
                  "",
              )
          )
          val previous =
              pullRequest()
                  .copy(
                      reviewRevision =
                          ReviewRevision(
                              "rv-1",
                              1,
                              "설명",
                              threads =
                                  listOf(
                                      ReviewThread(
                                          "PRRT_external",
                                          ReviewLevel.R,
                                          listOf(
                                              ReviewComment(
                                                  "PRRC_external",
                                                  Actor("reviewer-1", ActorKind.HUMAN, "Reviewer"),
                                                  "[R] 외부에서 확인이 필요합니다",
                                                  createdAtEpochMillis = 1_790_035_200_000,
                                                  path = "src/Main.kt",
                                                  line = 8,
                                              )
                                          ),
                                      )
                                  ),
                          )
                  )

          val result =
              GithubReviewAdapter(
                      Path.of("/repo"),
                      runner,
                      currentPullRequest = { previous },
                  )
                  .get(GetReviewRequest("17"))

          val current =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          current.reviewRevision.id shouldBe "rv-1"
          current.reviewRevision.number shouldBe 1
        }
      }

      context("Review 코멘트를 추가하면") {
        test("일반 pull request 코멘트를 추가하면, issue comment를 도메인 thread로 복원합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, """{"id":501,"node_id":"IC_remote"}""", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """
                  {
                    "number":17,
                    "title":"기능 추가",
                    "body":"설명",
                    "state":"OPEN",
                    "isDraft":false,
                    "baseRefName":"main",
                    "headRefName":"sk-27",
                    "headRefOid":"diff-1",
                    "comments":[{"id":"IC_remote","databaseId":501,"body":"[C] 확인했습니다","author":{"login":"human-1"},"createdAt":"2026-09-22T00:00:00Z"}]
                  }
                  """
                      .trimIndent(),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val comment = ReviewComment("comment-1", Actor("human-1", ActorKind.HUMAN), "확인했습니다")
          val request =
              AddReviewCommentRequest("17", "rv-1", comment.author, ReviewLevel.C, comment)

          val result =
              GithubReviewAdapter(
                      Path.of("/repo"),
                      runner,
                      currentPullRequest = { pullRequest() },
                  )
                  .comment(request)

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<AddReviewCommentResponse>()
          response.threadId shouldBe "github-issue-comment-IC_remote"
          response.reviewRevision.threads.single().comments.single().id shouldBe "IC_remote"
          runner.commands.first().tokens shouldBe
              listOf(
                  "gh",
                  "api",
                  "--method",
                  "POST",
                  "repos/{owner}/{repo}/issues/17/comments",
                  "-f",
                  "body=[C] 확인했습니다",
              )
        }

        test("코드 줄과 수준을 지정하면, 현재 diff identity로 리뷰 코멘트를 생성합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, """{"id":101,"node_id":"PRRC_remote"}""", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_remote",
                      commentId = "PRRC_remote",
                      databaseId = 101,
                      body = "[Agent] [R] 수정이 필요합니다",
                      path = "src/Main.kt",
                      line = 12,
                  ),
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

          val response =
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
          response
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<AddReviewCommentResponse>()
              .threadId shouldBe "PRRT_remote"
        }
      }

      context("GitHub review thread를 답변하거나 해결하면") {
        test("provider review thread node ID를 전달하면, 답변 mutation에 같은 ID를 사용합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_remote",
                      commentId = "PRRC_remote",
                      databaseId = 301,
                      body = "[C] 답변",
                      path = "src/Main.kt",
                      line = 12,
                  ),
                  "",
              )
          )
          val current = remoteThreadPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .reply(
                      ReplyReviewThreadRequest(
                          pullRequestId = "17",
                          reviewRevisionId = "rv-1",
                          threadId = "PRRT_remote",
                          comment =
                              ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "답변"),
                      )
                  )

          result.shouldBeTypeOf<PortResult.Success<*>>()
          runner.commands[0].tokens shouldContainExactly
              listOf(
                  "gh",
                  "api",
                  "graphql",
                  "-f",
                  "query=mutation(\u0024subjectId:ID!,\u0024body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:\u0024subjectId,body:\u0024body}){comment{id}}}",
                  "-f",
                  "subjectId=PRRT_remote",
                  "-f",
                  "body=[C] 답변",
              )
        }

        test("로컬 comment ID를 thread ID로 사용하면, GraphQL mutation 없이 지원 불가를 반환합니다") {
          val runner = RecordingCommandRunner()
          val localThread =
              ReviewThread(
                  id = "comment-1",
                  level = ReviewLevel.C,
                  comments =
                      listOf(
                          ReviewComment(
                              "comment-1",
                              Actor("agent-1", ActorKind.AGENT),
                              "[Agent] 확인했습니다",
                          )
                      ),
              )
          val current =
              pullRequest()
                  .copy(
                      reviewRevision =
                          pullRequest().reviewRevision.copy(threads = listOf(localThread))
                  )
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .resolve(
                      ResolveReviewThreadRequest(
                          pullRequestId = "17",
                          reviewRevisionId = "rv-1",
                          threadId = "comment-1",
                          actor = Actor("agent-1", ActorKind.AGENT),
                      )
                  )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_GENERAL_COMMENT_UNSUPPORTED"
          runner.commands shouldBe emptyList()
        }

        test("provider review thread node ID를 전달하면, resolve mutation에 같은 ID를 사용합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(
              CommandResult(
                  0,
                  reviewThreadsResponse(
                      threadId = "PRRT_remote",
                      commentId = "PRRC_remote",
                      databaseId = 301,
                      body = "[C] 확인했습니다",
                      path = "src/Main.kt",
                      line = 12,
                      resolved = true,
                  ),
                  "",
              )
          )
          val result =
              GithubReviewAdapter(
                      Path.of("/repo"),
                      runner,
                      currentPullRequest = { remoteThreadPullRequest() },
                  )
                  .resolve(
                      ResolveReviewThreadRequest(
                          pullRequestId = "17",
                          reviewRevisionId = "rv-1",
                          threadId = "PRRT_remote",
                          actor = Actor("human-1", ActorKind.HUMAN),
                      )
                  )

          result.shouldBeTypeOf<PortResult.Success<*>>()
          runner.commands[0].tokens shouldContainExactly
              listOf(
                  "gh",
                  "api",
                  "graphql",
                  "-f",
                  "query=mutation(\u0024threadId:ID!){resolveReviewThread(input:{threadId:\u0024threadId}){thread{id}}}",
                  "-f",
                  "threadId=PRRT_remote",
              )
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

private fun remoteThreadPullRequest(): PullRequest =
    pullRequest()
        .copy(
            reviewRevision =
                ReviewRevision(
                    "rv-1",
                    1,
                    "설명",
                    threads =
                        listOf(
                            ReviewThread(
                                id = "PRRT_remote",
                                level = ReviewLevel.C,
                                comments =
                                    listOf(
                                        ReviewComment(
                                            id = "PRRC_remote",
                                            author = Actor("human-1", ActorKind.HUMAN),
                                            body = "[C] 확인했습니다",
                                            path = "src/Main.kt",
                                            line = 12,
                                        )
                                    ),
                            )
                        ),
                )
        )

private fun reviewThreadsResponse(
    threadId: String,
    commentId: String,
    databaseId: Long,
    body: String,
    path: String,
    line: Int,
    resolved: Boolean = false,
): String =
    """
    [{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"$threadId","isResolved":$resolved,"comments":{"nodes":[{"id":"$commentId","databaseId":$databaseId,"body":"$body","author":{"login":"reviewer-1","name":"Reviewer"},"createdAt":"2026-09-22T00:00:00Z","path":"$path","line":$line}]} }],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]
    """
        .trimIndent()

private fun emptyReviewThreadsResponse(): String =
    """
    [{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]
    """
        .trimIndent()

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
