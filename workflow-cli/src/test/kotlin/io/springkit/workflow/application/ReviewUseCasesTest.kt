package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestState
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.ThreadState
import io.springkit.workflow.domain.WorkflowResult

class ReviewUseCasesTest :
    FunSpec({
      context("리뷰 본문을 수정하는 상황에서") {
        test("코드가 바뀌지 않으면, 기존 코드 게이트를 유지합니다") {
          val before = pullRequest()
          val after =
              before.copy(
                  body = "new body",
                  reviewRevision = revision("rv-2", "new body"),
              )
          val port =
              FakeReviewPort(before, UpdateReviewResponse(after, codeChanged = false, receipt()))

          val result =
              ReviewUseCases(port)
                  .update(
                      UpdateReviewRequest(
                          pullRequestId = before.id,
                          expectedReviewRevisionId = "rv-1",
                          body = "new body",
                      ),
                  )

          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<UpdateReviewResponse>>().data
          response.pullRequest.changeRevision.id shouldBe "cr-1"
          response.pullRequest.ci shouldBe CiStatus.PASSED
          response.pullRequest.aiReview shouldBe AiReviewStatus.PASSED
          response.pullRequest.approval?.active shouldBe true
        }

        test("코드가 바뀌면, 새 변경 리비전을 만들고 게이트를 무효화합니다") {
          val before = pullRequest()
          val after =
              before.copy(
                  reviewRevision = revision("rv-2", "same body"),
                  changeRevision = ChangeRevision("cr-2", 2, Diff("diff-2")),
              )
          val port =
              FakeReviewPort(before, UpdateReviewResponse(after, codeChanged = true, receipt()))

          val result =
              ReviewUseCases(port)
                  .update(
                      UpdateReviewRequest(
                          pullRequestId = before.id,
                          expectedReviewRevisionId = "rv-1",
                          changeRevision = after.changeRevision,
                      ),
                  )

          val response =
              result.shouldBeInstanceOf<WorkflowResult.Success<UpdateReviewResponse>>().data
          response.pullRequest.changeRevision.id shouldBe "cr-2"
          response.pullRequest.ci shouldBe CiStatus.PENDING
          response.pullRequest.aiReview shouldBe AiReviewStatus.PENDING
          response.pullRequest.approval shouldBe null
        }

        test("오래된 리비전을 지정하면, 제공자 갱신 전에 오래된 리비전 오류로 거부합니다") {
          val before = pullRequest()
          val port = FakeReviewPort(before, null)

          val result =
              ReviewUseCases(port)
                  .update(
                      UpdateReviewRequest(
                          pullRequestId = before.id,
                          expectedReviewRevisionId = "rv-old",
                          body = "new body",
                      ),
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.STALE_REVISION
          port.updateCalls shouldBe 0
        }
      }

      context("리뷰 댓글을 등록하는 상황에서") {
        test("에이전트 댓글을 등록하면, 게시 전에 댓글 본문에 [Agent]를 표시합니다") {
          val before = pullRequest()
          val port = FakeReviewPort(before, null)
          val actor = Actor("agent-1", ActorKind.AGENT)
          val comment = ReviewComment("comment-1", actor, "확인이 필요합니다.")

          ReviewUseCases(port)
              .comment(AddReviewCommentRequest("pr-1", "rv-1", actor, ReviewLevel.R, comment))

          port.lastComment?.comment?.body shouldBe "[Agent] 확인이 필요합니다."
        }
      }

      context("리뷰 스레드를 해결하는 상황에서") {
        test("사람 댓글이 포함된 스레드를 에이전트가 해결하면, 사람 확인 필요 오류로 거부합니다") {
          val human = Actor("human-1", ActorKind.HUMAN)
          val thread =
              ReviewThread(
                  id = "thread-1",
                  level = ReviewLevel.R,
                  comments = listOf(ReviewComment("comment-1", human, "수정이 필요합니다.")),
              )
          val before =
              pullRequest()
                  .copy(reviewRevision = revision("rv-1", "body").copy(threads = listOf(thread)))
          val port = FakeReviewPort(before, null)

          val result =
              ReviewUseCases(port)
                  .resolve(
                      ResolveReviewThreadRequest(
                          "pr-1",
                          "rv-1",
                          "thread-1",
                          Actor("agent-1", ActorKind.AGENT),
                      ),
                  )

          result.shouldBeInstanceOf<WorkflowResult.Failure>().data.code shouldBe
              io.springkit.workflow.domain.FailureCode.HUMAN_REQUIRED
          port.resolveCalls shouldBe 0
        }
      }
    })

private fun pullRequest(): PullRequest =
    PullRequest(
        id = "pr-1",
        subTaskId = "sk-27",
        title = "Review",
        body = "body",
        base = "main",
        state = PullRequestState.REVIEW,
        risk = Risk.NORMAL,
        exposure = Exposure.UNCHANGED,
        reviewRevision = revision("rv-1", "body"),
        changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
        approval =
            Approval(
                id = "approval-1",
                actor = Actor("human-1", ActorKind.HUMAN),
                changeRevisionId = "cr-1",
                diffIdentity = "diff-1",
            ),
        ci = CiStatus.PASSED,
        aiReview = AiReviewStatus.PASSED,
    )

private fun revision(id: String, body: String): ReviewRevision =
    ReviewRevision(id = id, number = id.removePrefix("rv-").toLong(), body = body)

private fun receipt(): ChangeReceipt = ChangeReceipt("change-1", "review-update")

private class FakeReviewPort(
    private var current: PullRequest,
    private val updateResponse: UpdateReviewResponse?,
) : ReviewPort {
  var updateCalls: Int = 0
  var resolveCalls: Int = 0
  var lastComment: AddReviewCommentRequest? = null

  override fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse> = unsupported()

  override fun get(request: GetReviewRequest): PortResult<GetReviewResponse> =
      PortResult.Success(GetReviewResponse(current))

  override fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse> {
    updateCalls += 1
    val response = updateResponse ?: return unsupported()
    current = response.pullRequest
    return PortResult.Success(response)
  }

  override fun comment(request: AddReviewCommentRequest): PortResult<AddReviewCommentResponse> {
    lastComment = request
    return PortResult.Success(
        AddReviewCommentResponse(
            current.reviewRevision,
            "thread-1",
            ChangeReceipt("change-comment", "review-comment"),
        )
    )
  }

  override fun reply(request: ReplyReviewThreadRequest): PortResult<ReplyReviewThreadResponse> =
      unsupported()

  override fun resolve(
      request: ResolveReviewThreadRequest,
  ): PortResult<ResolveReviewThreadResponse> {
    resolveCalls += 1
    return PortResult.Success(
        ResolveReviewThreadResponse(
            current.reviewRevision.copy(
                threads =
                    current.reviewRevision.threads.map {
                      if (it.id == request.threadId) it.copy(state = ThreadState.RESOLVED) else it
                    }
            ),
            ChangeReceipt("change-resolve", "review-resolve"),
        )
    )
  }

  override fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse> = unsupported()

  override fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse> =
      unsupported()

  private fun <T> unsupported(): PortResult<T> =
      PortResult.Failure(PortError("STATE_CONFLICT", "unsupported in test"))
}
