package io.springkit.workflow.adapter.github

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
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
      context("PR 변경 전에 gh 계정과 PR 작성자를 비교하면") {
        test("불일치 시 두 로그인과 재실행 옵션을 알리고 중단합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "agent-account\n", ""))
          runner.enqueue(CommandResult(0, """{"author":{"login":"Jeong-Rae"}}""", ""))
          val adapter = GithubReviewAdapter(Path.of("/repo"), runner)

          val failure = adapter.authorizeAccountMismatch("17").shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "GITHUB_ACCOUNT_MISMATCH"
          failure.error.message shouldContain "agent-account"
          failure.error.message shouldContain "Jeong-Rae"
          failure.error.message shouldContain "--allow-account-mismatch"
          runner.commands.map { it.tokens } shouldContainExactly
              listOf(
                  listOf("gh", "api", "user", "--jq", ".login"),
                  listOf("gh", "pr", "view", "17", "--json", "author"),
              )
        }

        test("명시적 override도 불일치를 경고하고 확인 절차만 건너뜁니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "agent-account\n", ""))
          runner.enqueue(CommandResult(0, """{"author":{"login":"Jeong-Rae"}}""", ""))
          val adapter = GithubReviewAdapter(Path.of("/repo"), runner)

          val warning =
              adapter
                  .authorizeAccountMismatch("17", allowAccountMismatch = true)
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value

          warning shouldBe
              "현재 gh 사용자 'agent-account'와 PR 작성자 'Jeong-Rae'가 다릅니다. " +
                  "--allow-account-mismatch로 해당 불일치를 허용해 PR 변경을 계속합니다."
          runner.commands.map { it.tokens }.none { it.contains("auth") } shouldBe true
        }

        test("같은 계정이면 경고 없이 통과합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "Jeong-Rae\n", ""))
          runner.enqueue(CommandResult(0, """{"author":{"login":"jeong-rae"}}""", ""))
          val adapter = GithubReviewAdapter(Path.of("/repo"), runner)

          val result = adapter.authorizeAccountMismatch("17")

          result shouldBe PortResult.Success(null)
        }
      }

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
                      changeRevision = changeRevision,
                      reviewRevision = reviewRevision,
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<OpenReviewResponse>()
          response.pullRequest.changeRevision shouldBe
              changeRevision.copy(providerRevision = "abc123")
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

        test("open 결과를 저장한 뒤 같은 head OID를 조회하면, change revision과 상태를 유지합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "https://github.com/example/repo/pull/17\n", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":true,"baseRefName":"main","headRefName":"sk-27","headRefOid":"head-1"}""",
                  "",
              )
          )
          var persisted: PullRequest? = null
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { persisted },
              )

          val opened =
              adapter
                  .open(
                      OpenReviewRequest(
                          subTaskId = "sk-27",
                          title = "기능 추가",
                          body = "설명",
                          base = "main",
                          branch = "sk-27",
                          risk = Risk.NORMAL,
                          changeRevision = ChangeRevision("cr-1", 1, Diff("local-fp")),
                          reviewRevision = ReviewRevision("rv-1", 1, "설명"),
                      )
                  )
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<OpenReviewResponse>()
                  .pullRequest
          opened.changeRevision.providerRevision shouldBe "head-1"
          persisted =
              opened.copy(
                  approval =
                      Approval("ap-1", Actor("human-1", ActorKind.HUMAN), "cr-1", "local-fp"),
                  ci = CiStatus.PASSED,
              )
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":true,"baseRefName":"main","headRefName":"sk-27","headRefOid":"head-1"}""",
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))

          val current =
              adapter
                  .get(GetReviewRequest("17"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest

          current.changeRevision.id shouldBe "cr-1"
          current.changeRevision.number shouldBe 1
          current.changeRevision.providerRevision shouldBe "head-1"
          current.ci shouldBe CiStatus.PASSED
          current.approval shouldBe persisted.approval
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
                      authorAssociation = "COLLABORATOR",
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
          pullRequest.changeRevision.diff.identity shouldBe "github-untracked-pr-17"
          pullRequest.changeRevision.providerRevision shouldBe "abc123"
          pullRequest.reviewRevision.threads.single().id shouldBe "PRRT_external"
          pullRequest.reviewRevision.threads.single().comments.single().id shouldBe "PRRC_external"
          pullRequest.reviewRevision.threads.single().comments.single().author.kind shouldBe
              ActorKind.HUMAN
          runner.commands[1]
              .tokens
              .first { it.startsWith("query=") }
              .contains("authorAssociation") shouldBe true
        }

        test("PR review thread는 Agent 접두사가 사람 작성자 메타데이터보다 우선합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"abc123"}""",
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
                      body = "[Agent] [R] 자동 검토 의견",
                      path = "src/Main.kt",
                      line = 8,
                      authorLogin = "human-1",
                      authorAssociation = "COLLABORATOR",
                  ),
                  "",
              )
          )

          val pullRequest =
              GithubReviewAdapter(Path.of("/repo"), runner, humanActorIds = setOf("human-1"))
                  .get(GetReviewRequest("17"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest

          pullRequest.reviewRevision.threads.single().comments.single().author.kind shouldBe
              ActorKind.AGENT
        }

        test("저장된 provider SHA와 GitHub head OID가 다르면, 새 change revision을 반환합니다") {
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
          current.changeRevision.diff.identity shouldBe "local-fingerprint"
          current.changeRevision.providerRevision shouldBe "diff-2"
          current.ci.name shouldBe "PENDING"
        }

        test("provider SHA가 저장되지 않았으면 diff identity가 같아도 코드 변경을 보수적으로 판단합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1"}""",
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val legacy =
              pullRequest()
                  .copy(
                      changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
                  )

          val current =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { legacy })
                  .get(GetReviewRequest("17"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest

          current.changeRevision.number shouldBe 2
          current.changeRevision.providerRevision shouldBe "diff-1"
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

        test("코드 줄과 수준을 지정하면, provider revision으로 리뷰 코멘트를 생성합니다") {
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
          val current =
              pullRequest()
                  .copy(
                      changeRevision =
                          pullRequest().changeRevision.copy(providerRevision = "head-1")
                  )
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
                  "commit_id=head-1",
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

        test("provider SHA가 없으면 코드 줄 코멘트를 GitHub에 게시하지 않습니다") {
          val runner = RecordingCommandRunner()
          val current =
              pullRequest()
                  .copy(
                      changeRevision = ChangeRevision("cr-1", 1, Diff("local-fingerprint")),
                  )
          val comment =
              ReviewComment(
                  "comment-1",
                  Actor("agent-1", ActorKind.AGENT),
                  "[Agent] 수정이 필요합니다",
                  path = "src/Main.kt",
                  line = 12,
              )

          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .comment(
                      AddReviewCommentRequest("17", "rv-1", comment.author, ReviewLevel.R, comment)
                  )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe
              "GITHUB_REVIEW_REVISION_UNAVAILABLE"
          runner.commands shouldBe emptyList()
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
                      body = "[C] 답변\n\n<!-- springkit:review-thread-reply:reply-1 -->",
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
                  "body=[C] 답변\n\n<!-- springkit:review-thread-reply:reply-1 -->",
              )
        }

        test("같은 ID와 본문의 원격 답글만 있으면, 기존 revision을 복구할 수 있습니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[R] 확인이 필요합니다",
                      "[Agent] [R] 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .recoverReply(
                      ReplyReviewThreadRequest(
                          "17",
                          "rv-1",
                          "github-issue-comment-IC_root",
                          ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "[Agent] 답변"),
                      ),
                  )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ReplyReviewThreadResponse>()
          response.reviewRevision.number shouldBe 2
          response.reviewRevision.threads.single().comments.map { it.body } shouldBe
              listOf("[R] 확인이 필요합니다", "[Agent] [R] 답변")
        }

        test("답글 외에 다른 스레드가 바뀌면, 원격 revision 복구를 거부합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1","comments":[{"id":"IC_root","node_id":"IC_root","body":"[R] 확인이 필요합니다","author":{"login":"agent-1"},"authorAssociation":"BOT","createdAt":"2026-09-22T00:00:00Z"},{"id":"IC_reply","node_id":"IC_reply","body":"[Agent] [R] 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->","author":{"login":"agent-1"},"authorAssociation":"BOT","createdAt":"2026-09-22T00:01:00Z"},{"id":"IC_other","node_id":"IC_other","body":"[C] 새 스레드","author":{"login":"agent-1"},"authorAssociation":"BOT","createdAt":"2026-09-22T00:02:00Z"}]}""",
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .recoverReply(
                      ReplyReviewThreadRequest(
                          "17",
                          "rv-1",
                          "github-issue-comment-IC_root",
                          ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "[Agent] 답변"),
                      ),
                  )

          result.shouldBeTypeOf<PortResult.Success<*>>().value shouldBe null
        }

        test("같은 답글 ID에 다른 본문이 있으면, ID 충돌로 거부합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[R] 확인이 필요합니다",
                      "[Agent] [R] 다른 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .recoverReply(
                      ReplyReviewThreadRequest(
                          "17",
                          "rv-1",
                          "github-issue-comment-IC_root",
                          ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "[Agent] 답변"),
                      ),
                  )

          result.shouldBeTypeOf<PortResult.Failure>().error.code shouldBe
              "GITHUB_REPLY_IDEMPOTENCY_CONFLICT"
        }

        test("Agent 접두사가 있는 HUMAN 계정 코멘트는 agent가 해결할 수 있습니다") {
          val runner = RecordingCommandRunner()
          val providerComment =
              issueCommentsPullRequest(
                  "[Agent] [C] 자동 검토 의견",
                  authorLogin = "Human-1",
                  authorAssociation = "COLLABORATOR",
              )
          runner.enqueue(CommandResult(0, providerComment, ""))
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          runner.enqueue(CommandResult(0, providerComment, ""))
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[Agent] [C] 자동 검토 의견\n\n<!-- springkit:issue-comment-resolved -->",
                      authorLogin = "Human-1",
                      authorAssociation = "COLLABORATOR",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          var current: PullRequest? = null
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { current },
                  humanActorIds = setOf("human-1"),
              )

          val pullRequest =
              adapter
                  .get(GetReviewRequest("17"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          current = pullRequest
          val thread = pullRequest.reviewRevision.threads.single()

          thread.comments.single().author.kind shouldBe ActorKind.AGENT
          thread.requiresHumanResolution shouldBe false
          val result =
              adapter.resolve(
                  ResolveReviewThreadRequest(
                      pullRequestId = "17",
                      reviewRevisionId = pullRequest.reviewRevision.id,
                      threadId = thread.id,
                      actor = Actor("agent-1", ActorKind.AGENT),
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ResolveReviewThreadResponse>()
          response.reviewRevision.threads.single().state.name shouldBe "RESOLVED"
          runner.commands.size shouldBe 6
        }

        test("Agent 접두사가 없는 HUMAN 계정 코멘트는 agent 해결을 거부합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[C] 사람 검토 의견",
                      authorLogin = "human-1",
                      authorAssociation = "COLLABORATOR",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          var current: PullRequest? = null
          val adapter =
              GithubReviewAdapter(
                  Path.of("/repo"),
                  runner,
                  currentPullRequest = { current },
                  humanActorIds = setOf("human-1"),
              )
          val pullRequest =
              adapter
                  .get(GetReviewRequest("17"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetReviewResponse>()
                  .pullRequest
          current = pullRequest
          val thread = pullRequest.reviewRevision.threads.single()

          thread.comments.single().author.kind shouldBe ActorKind.HUMAN
          thread.requiresHumanResolution shouldBe true
          val result =
              adapter.resolve(
                  ResolveReviewThreadRequest(
                      pullRequestId = "17",
                      reviewRevisionId = pullRequest.reviewRevision.id,
                      threadId = thread.id,
                      actor = Actor("agent-1", ActorKind.AGENT),
                  )
              )

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "HUMAN_REQUIRED"
          runner.commands.size shouldBe 2
        }

        test("일반 pull request 코멘트에 답변하면, root thread에 답글을 묶어 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, issueCommentsPullRequest("[R] 확인이 필요합니다"), ""))
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          runner.enqueue(CommandResult(0, "{\"id\":502,\"node_id\":\"IC_reply\"}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[R] 확인이 필요합니다",
                      "[R] 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .reply(
                      ReplyReviewThreadRequest(
                          pullRequestId = "17",
                          reviewRevisionId = "rv-1",
                          threadId = "github-issue-comment-IC_root",
                          comment =
                              ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "답변"),
                      )
                  )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ReplyReviewThreadResponse>()
          response.reviewRevision.threads.single().comments.map { it.body } shouldBe
              listOf("[R] 확인이 필요합니다", "[R] 답변")
          runner.commands[2].tokens shouldContainExactly
              listOf(
                  "gh",
                  "api",
                  "--method",
                  "POST",
                  "repos/{owner}/{repo}/issues/17/comments",
                  "-f",
                  "body=[R] 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->",
              )
        }

        test("답글 게시 뒤 재조회가 실패하고 같은 답글을 재시도하면, 기존 답글을 재사용합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, issueCommentsPullRequest("[R] 확인이 필요합니다"), ""))
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          runner.enqueue(CommandResult(0, "{\"id\":502,\"node_id\":\"IC_reply\"}", ""))
          runner.enqueue(CommandResult(1, "", "temporary fetch failure"))
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[R] 확인이 필요합니다",
                      "[R] 답변\n\n<!-- springkit:issue-comment-root:IC_root -->\n\n<!-- springkit:issue-comment-reply:reply-1 -->",
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val adapter =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
          val request =
              ReplyReviewThreadRequest(
                  pullRequestId = "17",
                  reviewRevisionId = "rv-1",
                  threadId = "github-issue-comment-IC_root",
                  comment = ReviewComment("reply-1", Actor("agent-1", ActorKind.AGENT), "답변"),
              )

          adapter.reply(request).shouldBeTypeOf<PortResult.Failure>()
          val retry = adapter.reply(request)

          val response =
              retry
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ReplyReviewThreadResponse>()
          response.reviewRevision.threads.single().comments.map { it.body } shouldBe
              listOf("[R] 확인이 필요합니다", "[R] 답변")
          runner.commands.count {
            it.tokens.firstOrNull() == "gh" &&
                it.tokens.getOrNull(1) == "api" &&
                it.tokens.getOrNull(3) == "POST"
          } shouldBe 1
        }

        test("일반 pull request 코멘트를 해결하면, 숨은 marker로 해결 상태를 복원합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, issueCommentsPullRequest("[C] 확인했습니다"), ""))
          runner.enqueue(CommandResult(0, "{}", ""))
          runner.enqueue(
              CommandResult(
                  0,
                  issueCommentsPullRequest(
                      "[C] 확인했습니다\n\n<!-- springkit:issue-comment-resolved -->"
                  ),
                  "",
              )
          )
          runner.enqueue(CommandResult(0, emptyReviewThreadsResponse(), ""))
          val current = issueCommentPullRequest()
          val result =
              GithubReviewAdapter(Path.of("/repo"), runner, currentPullRequest = { current })
                  .resolve(
                      ResolveReviewThreadRequest(
                          pullRequestId = "17",
                          reviewRevisionId = "rv-1",
                          threadId = "github-issue-comment-IC_root",
                          actor = Actor("agent-1", ActorKind.AGENT),
                      )
                  )

          val thread =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ResolveReviewThreadResponse>()
                  .reviewRevision
                  .threads
                  .single()
          thread.state.name shouldBe "RESOLVED"
          thread.comments.single().body shouldBe "[C] 확인했습니다"
          runner.commands[1].tokens shouldContainExactly
              listOf(
                  "gh",
                  "api",
                  "graphql",
                  "-f",
                  "query=mutation(\u0024issueCommentId:ID!,\u0024body:String!){updateIssueComment(input:{id:\u0024issueCommentId,body:\u0024body}){issueComment{id}}}",
                  "-f",
                  "issueCommentId=IC_root",
                  "-f",
                  "body=[C] 확인했습니다\n\n<!-- springkit:issue-comment-resolved -->",
              )
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
          val nextChange = ChangeRevision("cr-2", 2, Diff("diff-2"), providerRevision = "diff-2")
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

        test("COMPLETED 상태에 conclusion이 없으면, 성공으로 간주하지 않습니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"headRefOid":"abc123","statusCheckRollup":[{"name":"build","status":"COMPLETED"}]}""",
                  "",
              )
          )

          val response =
              GithubCiAdapter(Path.of("/repo"), runner)
                  .get(GetCiRequest("17", "abc123"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetCiResponse>()

          response.runs.single().status.name shouldBe "PENDING"
          response.status.name shouldBe "PENDING"
        }

        test("CheckRun conclusion 없이 상태형 status context를 성공과 실패로 변환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(
              CommandResult(
                  0,
                  """{"headRefOid":"abc123","statusCheckRollup":[{"context":"legacy-success","state":"SUCCESS"},{"context":"legacy-failure","state":"FAILURE"},{"context":"legacy-error","state":"ERROR"}]}""",
                  "",
              )
          )

          val response =
              GithubCiAdapter(Path.of("/repo"), runner)
                  .get(GetCiRequest("17", "abc123"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetCiResponse>()

          response.runs.map { it.status.name } shouldBe listOf("PASSED", "FAILED", "FAILED")
          response.status.name shouldBe "FAILED"
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
        changeRevision =
            ChangeRevision("cr-1", 1, Diff("local-fingerprint"), providerRevision = "diff-1"),
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

private fun issueCommentPullRequest(): PullRequest =
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
                                id = "github-issue-comment-IC_root",
                                level = ReviewLevel.R,
                                comments =
                                    listOf(
                                        ReviewComment(
                                            "IC_root",
                                            Actor("agent-1", ActorKind.AGENT),
                                            "[R] 확인이 필요합니다",
                                            createdAtEpochMillis = 1_790_035_200_000,
                                        )
                                    ),
                            )
                        ),
                ),
        )

private fun issueCommentsPullRequest(
    rootBody: String,
    replyBody: String? = null,
    authorLogin: String = "agent-1",
    authorAssociation: String = "BOT",
): String {
  fun encode(value: String): String =
      value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
  val comments =
      listOfNotNull(
              """{"id":"IC_root","node_id":"IC_root","body":"${encode(rootBody)}","author":{"login":"$authorLogin"},"authorAssociation":"$authorAssociation","createdAt":"2026-09-22T00:00:00Z"}""",
              replyBody?.let {
                """{"id":"IC_reply","node_id":"IC_reply","databaseId":502,"body":"${encode(it)}","author":{"login":"agent-1"},"authorAssociation":"BOT","createdAt":"2026-09-22T00:01:00Z"}"""
              },
          )
          .joinToString(",")
  return """{"number":17,"title":"기능 추가","body":"설명","state":"OPEN","isDraft":false,"baseRefName":"main","headRefName":"sk-27","headRefOid":"diff-1","comments":[$comments]}"""
}

private fun reviewThreadsResponse(
    threadId: String,
    commentId: String,
    databaseId: Long,
    body: String,
    path: String,
    line: Int,
    authorLogin: String = "reviewer-1",
    authorAssociation: String = "COLLABORATOR",
    resolved: Boolean = false,
): String =
    """
    [{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"$threadId","isResolved":$resolved,"comments":{"nodes":[{"id":"$commentId","databaseId":$databaseId,"body":"$body","author":{"login":"$authorLogin","name":"Reviewer"},"authorAssociation":"$authorAssociation","createdAt":"2026-09-22T00:00:00Z","path":"$path","line":$line}]} }],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}]
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
