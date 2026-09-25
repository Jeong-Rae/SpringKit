package io.springkit.workflow.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.adapter.cli.WorkflowCommandRequest
import io.springkit.workflow.adapter.store.OkioWorkflowStoreAdapter
import io.springkit.workflow.application.AddReviewCommentRequest
import io.springkit.workflow.application.AddReviewCommentResponse
import io.springkit.workflow.application.ApproveReviewRequest
import io.springkit.workflow.application.ApproveReviewResponse
import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.DeliveryGateUseCases
import io.springkit.workflow.application.GetReviewRequest
import io.springkit.workflow.application.GetReviewResponse
import io.springkit.workflow.application.OpenReviewRequest
import io.springkit.workflow.application.OpenReviewResponse
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReadyReviewRequest
import io.springkit.workflow.application.ReadyReviewResponse
import io.springkit.workflow.application.ReplyReviewThreadRequest
import io.springkit.workflow.application.ReplyReviewThreadResponse
import io.springkit.workflow.application.ResolveReviewThreadRequest
import io.springkit.workflow.application.ResolveReviewThreadResponse
import io.springkit.workflow.application.ReviewGateUseCases
import io.springkit.workflow.application.ReviewLifecycleUseCases
import io.springkit.workflow.application.ReviewPort
import io.springkit.workflow.application.ReviewUseCases
import io.springkit.workflow.application.StackSyncUseCases
import io.springkit.workflow.application.StartCheckUseCases
import io.springkit.workflow.application.StatusUseCase
import io.springkit.workflow.application.StoreScope
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreSnapshotResponse
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreWriteRequest
import io.springkit.workflow.application.UpdateReviewRequest
import io.springkit.workflow.application.UpdateReviewResponse
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkspacePath
import java.lang.reflect.Proxy
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class WorkflowCommandGatewayReplyTest :
    FunSpec({
      context("같은 review reply CLI 요청을 다시 실행하면") {
        test("POST 후 조회가 실패해 Store가 롤백되어도, 재시도는 POST 없이 원격 답글을 복구합니다") {
          val store =
              OkioWorkflowStoreAdapter(FakeFileSystem(), "/workflow/reply-retry.json".toPath())
          val fixture = replyGateway(retryAfterPostFailure = true, storePort = store)
          val request = WorkflowCommandRequest.ReviewReply("rv-1", "thread-1", body = "답변입니다.")

          fixture.gateway.execute(request).shouldBeInstanceOf<WorkflowResult.Failure>()
          val beforeRetry =
              (store.snapshot(StoreSnapshotRequest(StoreScope.ALL))
                      as PortResult.Success<StoreSnapshotResponse>)
                  .value
                  .snapshot
                  .pullRequests
                  .single()
          beforeRetry.reviewRevision.id shouldBe "rv-1"

          fixture.gateway.execute(request).shouldBeInstanceOf<WorkflowResult.Success<*>>()

          val afterRetry =
              (store.snapshot(StoreSnapshotRequest(StoreScope.ALL))
                      as PortResult.Success<StoreSnapshotResponse>)
                  .value
                  .snapshot
                  .pullRequests
                  .single()
          afterRetry.reviewRevision.id shouldBe "rv-next"
          fixture.replies shouldHaveSize 1
          fixture.recoverCalls shouldHaveSize 1
        }

        test(
            "같은 pull request, revision, thread, 작성자와 본문에 같은 comment ID를 사용하고 Store ID를 발급하지 않습니다"
        ) {
          val fixture = replyGateway()
          val request =
              WorkflowCommandRequest.ReviewReply(
                  revision = "rv-1",
                  thread = "thread-1",
                  body = "답변입니다.",
              )

          fixture.gateway.execute(request).shouldBeInstanceOfSuccess()
          fixture.gateway.execute(request).shouldBeInstanceOfSuccess()

          fixture.replies.map { it.comment.id } shouldHaveSize 2
          fixture.replies[0].comment.id shouldBe fixture.replies[1].comment.id
          fixture.storeIdRequests.size shouldBe 0
        }

        test("본문, review revision 또는 작성자가 바뀌면 별도 comment ID를 사용합니다") {
          val first = replyGateway()
          first.gateway.execute(
              WorkflowCommandRequest.ReviewReply("rv-1", "thread-1", body = "답변입니다.")
          )
          first.gateway.execute(
              WorkflowCommandRequest.ReviewReply("rv-1", "thread-1", body = "수정된 답변입니다.")
          )
          val secondRevision = replyGateway(reviewRevisionId = "rv-2")
          secondRevision.gateway.execute(
              WorkflowCommandRequest.ReviewReply("rv-2", "thread-1", body = "답변입니다.")
          )
          val secondActor = replyGateway(actorId = "agent-2")
          secondActor.gateway.execute(
              WorkflowCommandRequest.ReviewReply("rv-1", "thread-1", body = "답변입니다.")
          )

          val ids =
              listOf(
                  first.replies[0].comment.id,
                  first.replies[1].comment.id,
                  secondRevision.replies.single().comment.id,
                  secondActor.replies.single().comment.id,
              )
          ids.distinct() shouldHaveSize ids.size
          listOf(first, secondRevision, secondActor).sumOf { it.storeIdRequests.size } shouldBe 0
        }
      }
    })

private data class ReplyGatewayFixture(
    val gateway: WorkflowCommandGateway,
    val replies: MutableList<ReplyReviewThreadRequest>,
    val storeIdRequests: MutableList<Unit>,
    val recoverCalls: MutableList<Unit>,
)

private fun replyGateway(
    reviewRevisionId: String = "rv-1",
    actorId: String = "agent-1",
    retryAfterPostFailure: Boolean = false,
    storePort: WorkflowStorePort? = null,
): ReplyGatewayFixture {
  val author = Actor("reviewer", ActorKind.HUMAN)
  val initialComment = ReviewComment("comment-1", author, "확인해 주세요.")
  val thread = ReviewThread("thread-1", ReviewLevel.R, listOf(initialComment))
  val reviewRevision = ReviewRevision(reviewRevisionId, 1, "검토", listOf(thread))
  val pullRequest =
      PullRequest(
          id = "pr-15",
          subTaskId = "sk-15",
          title = "변경",
          body = "설명",
          base = "develop",
          reviewRevision = reviewRevision,
          changeRevision = ChangeRevision("cr-1", 1, Diff("diff-1")),
      )
  val nextRevision =
      reviewRevision.copy(
          id = "rv-next",
          number = reviewRevision.number + 1,
          threads =
              listOf(
                  thread.reply(
                      ReviewComment(
                          "stable-comment",
                          Actor(actorId, ActorKind.AGENT),
                          "[Agent] 답변입니다.",
                      )
                  )
              ),
      )
  if (storePort != null) {
    val transaction = StoreTransactionRequest("seed-reply", "0", "seed-reply")
    storePort.begin(transaction)
    storePort.write(
        StoreWriteRequest(
            transactionId = transaction.transactionId,
            expectedRevision = "0",
            snapshot =
                WorkflowStoreSnapshot(
                    "0",
                    subTasks = listOf(SubTask("sk-15", "task-15", "review")),
                    pullRequests = listOf(pullRequest),
                ),
        )
    )
    storePort.commit(transaction)
  }
  val replies = mutableListOf<ReplyReviewThreadRequest>()
  var getCalls = 0
  val recoverCalls = mutableListOf<Unit>()
  val reviewPort =
      object : ReviewPort {
        override fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse> =
            unsupported()

        override fun get(request: GetReviewRequest): PortResult<GetReviewResponse> {
          getCalls += 1
          return PortResult.Success(
              GetReviewResponse(
                  if (retryAfterPostFailure && getCalls > 1)
                      pullRequest.copy(reviewRevision = nextRevision)
                  else pullRequest
              )
          )
        }

        override fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse> =
            unsupported()

        override fun comment(
            request: AddReviewCommentRequest
        ): PortResult<AddReviewCommentResponse> = unsupported()

        override fun reply(
            request: ReplyReviewThreadRequest
        ): PortResult<ReplyReviewThreadResponse> {
          replies += request
          if (retryAfterPostFailure) {
            return PortResult.Failure(
                io.springkit.workflow.application.PortError(
                    "GITHUB_REPLY_FAILED",
                    "POST succeeded but follow-up fetch failed",
                )
            )
          }
          return PortResult.Success(
              ReplyReviewThreadResponse(
                  reviewRevision =
                      reviewRevision.copy(id = "rv-next", number = reviewRevision.number + 1),
                  change = ChangeReceipt("change-${replies.size}", "reply"),
              )
          )
        }

        override fun recoverReply(
            request: ReplyReviewThreadRequest
        ): PortResult<ReplyReviewThreadResponse?> {
          recoverCalls += Unit
          return PortResult.Success(
              ReplyReviewThreadResponse(nextRevision, ChangeReceipt("change-recovered", "reply"))
          )
        }

        override fun resolve(
            request: ResolveReviewThreadRequest
        ): PortResult<ResolveReviewThreadResponse> = unsupported()

        override fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse> =
            unsupported()

        override fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse> =
            unsupported()
      }
  val context =
      object : WorkflowRuntimeContextResolver {
        override fun currentSubTaskId() = unsupported<PortResult<String>>()

        override fun currentWorkspaceId() = unsupported<PortResult<String>>()

        override fun currentWorkspacePath() = PortResult.Success(WorkspacePath("/workspace"))

        override fun currentReview() =
            PortResult.Success(
                WorkflowReviewContext(
                    "pr-15",
                    "sk-15",
                    "변경",
                    "develop",
                    "feature/sk-15",
                    reviewRevision,
                    pullRequest.changeRevision,
                )
            )

        override fun currentActor(requestId: String) =
            PortResult.Success(Actor(actorId, ActorKind.AGENT))
      }
  val storeIdRequests = mutableListOf<Unit>()
  val gateway =
      WorkflowCommandGateway(
          startCheck =
              StartCheckUseCases(
                  proxyPort(),
                  proxyPort(),
                  proxyPort(),
                  proxyPort(),
                  proxyPort(),
                  projectPrefix = "sk",
              ),
          review = ReviewUseCases(reviewPort, storePort = storePort),
          reviewLifecycle =
              ReviewLifecycleUseCases(
                  proxyPort(),
                  proxyPort(),
                  proxyPort(),
                  reviewPort,
                  proxyPort(),
                  storePort = proxyPort(),
              ),
          stackSync = StackSyncUseCases(proxyPort(), reviewPort, proxyPort()),
          status = StatusUseCase(proxyPort()),
          reviewGate = ReviewGateUseCases(proxyPort(), reviewPort, proxyPort()),
          deliveryGate = DeliveryGateUseCases(proxyPort(), proxyPort(), proxyPort(), proxyPort()),
          context = context,
          commentId = {
            storeIdRequests += Unit
            PortResult.Success("store-comment-id")
          },
      )
  return ReplyGatewayFixture(gateway, replies, storeIdRequests, recoverCalls)
}

private inline fun <reified T> proxyPort(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
      throw UnsupportedOperationException("Unexpected port call: ${method.name}")
    } as T

private fun <T> unsupported(): T =
    throw UnsupportedOperationException("Unexpected review port call")

private fun WorkflowResult<*>.shouldBeInstanceOfSuccess() {
  shouldBeInstanceOf<WorkflowResult.Success<*>>()
}
