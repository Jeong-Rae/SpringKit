package io.springkit.workflow.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.adapter.cli.WorkflowCommandRequest
import io.springkit.workflow.application.AuthorizeRequest
import io.springkit.workflow.application.AuthorizeResponse
import io.springkit.workflow.application.CurrentActorRequest
import io.springkit.workflow.application.CurrentActorResponse
import io.springkit.workflow.application.DeliveryGateUseCases
import io.springkit.workflow.application.IdentityPort
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReviewGateUseCases
import io.springkit.workflow.application.ReviewLifecycleUseCases
import io.springkit.workflow.application.ReviewPort
import io.springkit.workflow.application.ReviewUseCases
import io.springkit.workflow.application.StackSyncUseCases
import io.springkit.workflow.application.StartCheckUseCases
import io.springkit.workflow.application.StatusUseCase
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkspacePath
import java.lang.reflect.Proxy

class WorkflowCommandGatewayAccountMismatchTest :
    FunSpec({
      test("기존 PR 변경 명령은 application 부작용 전에 계정 비교를 수행합니다") {
        val checks = mutableListOf<Pair<String, Boolean>>()
        val gateway =
            accountMismatchGateway(
                check = { subTaskId, allow ->
                  checks += subTaskId to allow
                  PortResult.Failure(
                      PortError(
                          "GITHUB_ACCOUNT_MISMATCH",
                          "현재 gh 사용자와 PR 작성자가 다릅니다.",
                          target = subTaskId,
                      )
                  )
                }
            )
        val requests =
            listOf(
                WorkflowCommandRequest.ReviewUpdate("rv-8", allowAccountMismatch = true),
                WorkflowCommandRequest.ReviewComment(
                    "rv-8",
                    "R",
                    body = "의견",
                    allowAccountMismatch = true,
                ),
                WorkflowCommandRequest.ReviewReply(
                    "rv-8",
                    "thread-1",
                    body = "답변",
                    allowAccountMismatch = true,
                ),
                WorkflowCommandRequest.ReviewResolve(
                    "rv-8",
                    "thread-1",
                    allowAccountMismatch = true,
                ),
                WorkflowCommandRequest.Stack(requires = "sk-parent", allowAccountMismatch = true),
                WorkflowCommandRequest.Sync(continueSync = true, allowAccountMismatch = true),
                WorkflowCommandRequest.GateReady("sk-target", "rv-8", allowAccountMismatch = true),
                WorkflowCommandRequest.GateApprove(
                    "sk-target",
                    "cr-4",
                    allowAccountMismatch = true,
                ),
            )

        requests.forEach { request ->
          gateway.execute(request).shouldBeTypeOf<WorkflowResult.Failure>().data.code shouldBe
              FailureCode.EXTERNAL_FAILURE
        }

        checks shouldContainExactly
            listOf(
                "sk-current" to true,
                "sk-current" to true,
                "sk-current" to true,
                "sk-current" to true,
                "sk-current" to true,
                "sk-current" to true,
                "sk-target" to true,
                "sk-target" to true,
            )
      }

      test("override 불일치 경고를 보내고 기존 HUMAN gate는 계속 적용합니다") {
        val warnings = mutableListOf<String>()
        val humanOnlyIdentity =
            object : IdentityPort {
              override fun currentActor(
                  request: CurrentActorRequest
              ): PortResult<CurrentActorResponse> =
                  PortResult.Success(CurrentActorResponse(Actor("agent-1", ActorKind.AGENT)))

              override fun authorize(request: AuthorizeRequest): PortResult<AuthorizeResponse> =
                  PortResult.Success(
                      AuthorizeResponse(
                          allowed = false,
                          reason = "사람 Gate는 HUMAN principal만 수행할 수 있습니다.",
                      )
                  )
            }
        val warning =
            "현재 gh 사용자 'agent-1'와 PR 작성자 'Jeong-Rae'가 다릅니다. " +
                "--allow-account-mismatch로 해당 불일치를 허용해 PR 변경을 계속합니다."
        val gateway =
            accountMismatchGateway(
                check = { _, _ -> PortResult.Success(warning) },
                warningSink = warnings::add,
                identityPort = humanOnlyIdentity,
            )

        val result =
            gateway
                .execute(
                    WorkflowCommandRequest.GateReady(
                        "sk-target",
                        "rv-8",
                        allowAccountMismatch = true,
                    )
                )
                .shouldBeTypeOf<WorkflowResult.Failure>()

        warnings shouldContainExactly listOf(warning)
        result.data.code shouldBe FailureCode.HUMAN_REQUIRED
      }
    })

private fun accountMismatchGateway(
    check: (String, Boolean) -> PortResult<String?>,
    warningSink: (String) -> Unit = {},
    identityPort: IdentityPort = proxyPort(),
): WorkflowCommandGateway {
  val reviewPort = proxyPort<ReviewPort>()
  val storePort = proxyPort<WorkflowStorePort>()
  val context =
      object : WorkflowRuntimeContextResolver {
        override fun currentWorkspaceContext() =
            PortResult.Success(
                WorkflowWorkspaceContext("workspace-1", "sk-current", WorkspacePath("/workspace"))
            )

        override fun currentReview() =
            PortResult.Success(
                WorkflowReviewContext(
                    "pr-current",
                    "sk-current",
                    "변경",
                    "develop",
                    "feature/sk-current",
                    io.springkit.workflow.domain.ReviewRevision("rv-current", 1, "본문"),
                    io.springkit.workflow.domain.ChangeRevision(
                        "cr-current",
                        1,
                        io.springkit.workflow.domain.Diff("diff-current"),
                    ),
                )
            )

        override fun currentActor(requestId: String) =
            PortResult.Success(Actor("agent-1", ActorKind.AGENT))
      }

  return WorkflowCommandGateway(
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
              storePort = storePort,
          ),
      stackSync = StackSyncUseCases(proxyPort(), reviewPort, storePort),
      status = StatusUseCase(proxyPort()),
      reviewGate = ReviewGateUseCases(identityPort, reviewPort, storePort),
      deliveryGate = DeliveryGateUseCases(proxyPort(), proxyPort(), proxyPort(), proxyPort()),
      context = context,
      reviewAccountMismatchCheck = check,
      reviewAccountMismatchWarning = warningSink,
  )
}

private inline fun <reified T> proxyPort(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
      throw UnsupportedOperationException("Unexpected port call: ${method.name}")
    } as T
