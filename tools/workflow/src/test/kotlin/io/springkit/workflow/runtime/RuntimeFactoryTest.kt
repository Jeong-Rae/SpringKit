package io.springkit.workflow.runtime

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.adapter.cli.WorkflowCli
import io.springkit.workflow.adapter.cli.WorkflowCommandRequest
import io.springkit.workflow.adapter.github.GithubIssueTaskAdapter
import io.springkit.workflow.adapter.json.encodeToString
import io.springkit.workflow.adapter.local.SnapshotTaskAdapter
import io.springkit.workflow.adapter.provider.ProviderDeploymentAdapter
import io.springkit.workflow.adapter.store.OkioWorkflowStoreAdapter
import io.springkit.workflow.adapter.store.WorkflowStateJsonCodec
import io.springkit.workflow.application.DeploymentLifecycleUseCase
import io.springkit.workflow.application.PostMergeCleanupBlock
import io.springkit.workflow.application.PostMergeCleanupResponse
import io.springkit.workflow.application.PostMergeCleanupState
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewThread
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskState
import io.springkit.workflow.domain.ThreadState
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkflowState
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath

class RuntimeFactoryTest :
    FunSpec({
      context("Task provider를 런타임에 연결할 때") {
        test("설정이 없으면, 기존 snapshot adapter를 선택합니다") {
          val adapter =
              createTaskPort(
                  repositoryRoot = Path.of("/repo"),
                  environment = emptyMap(),
                  commandRunner = RuntimeCommandRunner(Path.of("/repo")),
                  snapshotProvider = { WorkflowStoreSnapshot("1") },
              )

          adapter.shouldBeInstanceOf<SnapshotTaskAdapter>()
        }

        test("snapshot을 지정하면, 기존 snapshot adapter를 선택합니다") {
          val adapter =
              createTaskPort(
                  repositoryRoot = Path.of("/repo"),
                  environment = mapOf("WORKFLOW_TASK_PROVIDER" to "snapshot"),
                  commandRunner = RuntimeCommandRunner(Path.of("/repo")),
                  snapshotProvider = { WorkflowStoreSnapshot("1") },
              )

          adapter.shouldBeInstanceOf<SnapshotTaskAdapter>()
        }

        test("github-issue를 지정하면, GitHub Issue adapter를 선택합니다") {
          val adapter =
              createTaskPort(
                  repositoryRoot = Path.of("/repo"),
                  environment = mapOf("WORKFLOW_TASK_PROVIDER" to "github-issue"),
                  commandRunner = RuntimeCommandRunner(Path.of("/repo")),
                  snapshotProvider = { WorkflowStoreSnapshot("1") },
              )

          adapter.shouldBeInstanceOf<GithubIssueTaskAdapter>()
        }

        test("지원하지 않는 provider이면, 두 기본 런타임 모두 명확한 예외를 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-provider-")
          val environment =
              mapOf(
                  "WORKFLOW_REPO_ROOT" to root.toString(),
                  "WORKFLOW_TASK_PROVIDER" to "clickup",
              )

          val applicationFailure =
              shouldThrow<IllegalArgumentException> {
                createDefaultApplicationRuntime(
                    currentDirectory = root,
                    environment = environment,
                    commandRunner = RuntimeCommandRunner(root),
                )
              }
          applicationFailure.message shouldContain "WORKFLOW_TASK_PROVIDER"

          shouldThrow<IllegalArgumentException> {
            createDefaultRuntime(
                currentDirectory = root,
                environment = environment,
                commandRunner = RuntimeCommandRunner(root),
            )
          }
        }
      }

      context("외부 provider CLI를 런타임에 연결할 때") {
        test("배포와 Release provider 명령을 JSON 배열로 설정하면, Application lifecycle을 구성합니다") {
          val root = Files.createTempDirectory("workflow-runtime-providers-")
          val environment =
              mapOf(
                  "WORKFLOW_REPO_ROOT" to root.toString(),
                  "WORKFLOW_DEPLOYMENT_COMMAND" to "[\"deploy\"]",
                  "WORKFLOW_RELEASE_COMMAND" to "[\"release\"]",
              )

          val runtime =
              createDefaultApplicationRuntime(
                  currentDirectory = root,
                  environment = environment,
                  commandRunner = RuntimeCommandRunner(root),
              )

          runtime.deploymentLifecycle shouldNotBe null
          runtime.releaseLifecycle shouldNotBe null
        }

        test("Release provider 명령이 빠지면, 명확한 IllegalArgumentException을 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-provider-missing-")
          val environment =
              mapOf(
                  "WORKFLOW_REPO_ROOT" to root.toString(),
                  "WORKFLOW_DEPLOYMENT_COMMAND" to "[\"deploy\"]",
              )

          val failure =
              shouldThrow<IllegalArgumentException> {
                createDefaultApplicationRuntime(
                    currentDirectory = root,
                    environment = environment,
                    commandRunner = RuntimeCommandRunner(root),
                )
              }

          failure.message shouldContain "WORKFLOW_RELEASE_COMMAND"
        }

        test("provider 명령이 JSON 배열이 아니면, 명확한 IllegalArgumentException을 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-provider-json-")
          val environment =
              mapOf(
                  "WORKFLOW_REPO_ROOT" to root.toString(),
                  "WORKFLOW_DEPLOYMENT_COMMAND" to "deploy",
                  "WORKFLOW_RELEASE_COMMAND" to "[\"release\"]",
              )

          val failure =
              shouldThrow<IllegalArgumentException> {
                createDefaultApplicationRuntime(
                    currentDirectory = root,
                    environment = environment,
                    commandRunner = RuntimeCommandRunner(root),
                )
              }

          failure.message shouldContain "WORKFLOW_DEPLOYMENT_COMMAND"
          failure.message shouldContain "JSON"
        }
      }

      context("병합 이후 cleanup과 배포가 모두 실패하면") {
        test("병합 이후 cleanup과 배포가 모두 실패하면, 배포 실패 결과에 cleanup 차단 원인과 재시도 행동을 보존합니다") {
          val deploymentFailure =
              WorkflowResult.Failure(
                  FailureData(
                      code = FailureCode.EXTERNAL_FAILURE,
                      message = "배포 provider 실패",
                      blockedBy = listOf(BlockedBy("DEPLOYMENT_FAILED", "배포 실패", "candidate-1")),
                  )
              )
          val cleanup =
              WorkflowResult.Success(
                  PostMergeCleanupResponse(
                      mergedSubTaskId = "sk-101",
                      state = PostMergeCleanupState.BLOCKED,
                      blocks =
                          listOf(
                              PostMergeCleanupBlock(
                                  phase = "worktree",
                                  target = "workspace-1",
                                  code = "WORKTREE_DIRTY",
                                  message = "Worktree에 게시되지 않은 변경이 있습니다.",
                              )
                          ),
                  ),
                  next = listOf(NextAction(ActorKind.WORKFLOW, "retry_cleanup")),
              )

          val combined = deploymentFailure.withCleanupRetry(cleanup, "sk-101")

          combined.data.blockedBy.map(BlockedBy::code) shouldContain "DEPLOYMENT_FAILED"
          combined.data.blockedBy.map(BlockedBy::code) shouldContain "WORKTREE_DIRTY"
          combined.data.next shouldContain NextAction(ActorKind.WORKFLOW, "retry_cleanup")
        }
      }

      context("Production 이후 다음 배포 후보를 읽을 때") {
        test("Store snapshot이 실패하면, STORE_FAILURE을 구조화해 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-next-candidate-store-failure-")
          val statePath = root.resolve("state.json")
          Files.writeString(statePath, "잘못된 상태")
          val store = OkioWorkflowStoreAdapter(statePath.toString().toPath())
          val deploymentLifecycle =
              DeploymentLifecycleUseCase(
                  storePort = store,
                  deploymentPort =
                      ProviderDeploymentAdapter(
                          commandPrefix = listOf("deploy"),
                          workingDirectory = root,
                          commandRunner = RuntimeCommandRunner(root),
                      ),
              )
          val production =
              DeploymentCandidate(
                  id = "candidate-1",
                  mainRevision = "main-1",
                  includedSubTasks = emptyList(),
                  validations = listOf(Validation("validation-1", "검증", ValidationStatus.PASSED)),
                  state = DeploymentCandidateState.PRODUCTION,
              )

          val result =
              createNextCandidateAfterProduction(
                  store = store,
                  deploymentLifecycle = deploymentLifecycle,
                  production = production,
                  requestId = "event-1",
              )

          val failure = result.shouldBeInstanceOf<WorkflowResult.Failure>()
          failure.data.code shouldBe FailureCode.STORE_FAILURE
          failure.data.blockedBy.single().target shouldBe "store"
        }
      }

      context("기본 런타임을 CLI에 연결할 때") {
        test("help를 요청하면, 공개 Workflow 명령 목록을 출력합니다") {
          val root = Files.createTempDirectory("workflow-runtime-help-")
          val output = ByteArrayOutputStream()
          val command =
              WorkflowCli(
                  createDefaultRuntime(
                      currentDirectory = root,
                      environment = mapOf("WORKFLOW_REPO_ROOT" to root.toString()),
                      commandRunner = RuntimeCommandRunner(root),
                  ),
                  stdout = PrintStream(output),
              )

          val result = command.test("--help")

          result.statusCode shouldBe 0
          output.toString() shouldContain "start"
          output.toString() shouldContain "review"
          output.toString() shouldContain "gate"
        }

        test("빈 Store에서 status JSON을 요청하면, 관리 Workspace 없음 실패를 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-empty-")
          val state = root.resolve("state.json")
          val gateway =
              createDefaultRuntime(
                  currentDirectory = root,
                  environment =
                      mapOf(
                          "WORKFLOW_REPO_ROOT" to root.toString(),
                          "WORKFLOW_STATE_FILE" to state.toString(),
                      ),
                  commandRunner = RuntimeCommandRunner(root),
              )

          val result = gateway.execute(WorkflowCommandRequest.Status())

          val failure =
              result.shouldBeInstanceOf<io.springkit.workflow.domain.WorkflowResult.Failure>()
          failure.data.code.name shouldBe "WORKSPACE_NOT_FOUND"
          result.encodeToString() shouldStartWith "{\"data\":{"
        }
      }

      context("Store에 현재 Workspace가 저장된 상태에서") {
        test("status 요청을 실행하면, 성공 JSON에 현재 SubTask를 포함합니다") {
          val root = Files.createTempDirectory("workflow-runtime-status-")
          val statePath = root.resolve("state.json")
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath(root.toString()), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "변경", workspace = workspace)
          val task =
              Task(
                  id = "task-1",
                  externalId = ExternalTaskId("TASK-1"),
                  title = "작업",
                  state = TaskState.IN_PROGRESS,
                  subTaskIds = listOf(subTask.id),
              )
          Files.writeString(
              statePath,
              WorkflowStateJsonCodec.encodeToString(
                  WorkflowState(
                      tasks = mapOf(task.id to task),
                      subTasks = mapOf(subTask.id to subTask),
                  )
              ),
          )
          val gateway =
              createDefaultRuntime(
                  currentDirectory = root,
                  environment =
                      mapOf(
                          "WORKFLOW_REPO_ROOT" to root.toString(),
                          "WORKFLOW_STATE_FILE" to statePath.toString(),
                      ),
                  commandRunner = RuntimeCommandRunner(root),
              )

          val result = gateway.execute(WorkflowCommandRequest.Status())

          val success =
              result.shouldBeInstanceOf<
                  io.springkit.workflow.domain.WorkflowResult.Success<JsonObject>
              >()
          success.data["task"].toString() shouldBe "\"TASK-1\""
          success.data["subtask"].toString() shouldBe "\"sk-101\""
          success.data["branch"].toString() shouldBe "\"sk-101\""
          success.data["state"].toString() shouldBe "\"DEVELOPMENT\""
          success.data["workspace"].toString() shouldContain "\"path\":\"${root}\""
          success.data["next"].toString() shouldBe "[]"
        }

        test("Review가 없는 SubTask에서 review open을 요청하면, Draft PR 결과를 반환합니다") {
          val root = Files.createTempDirectory("workflow-runtime-review-")
          val statePath = root.resolve("state.json")
          val bodyPath = root.resolve("pr.md")
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath(root.toString()), "sk-101")
          val subTask = SubTask("sk-101", "task-1", "변경", workspace = workspace)
          val task =
              Task(
                  id = "task-1",
                  externalId = ExternalTaskId("TASK-1"),
                  title = "작업",
                  subTaskIds = listOf(subTask.id),
              )
          Files.writeString(
              statePath,
              WorkflowStateJsonCodec.encodeToString(
                  WorkflowState(
                      tasks = mapOf(task.id to task),
                      subTasks = mapOf(subTask.id to subTask),
                  )
              ),
          )
          Files.writeString(
              bodyPath,
              listOf(
                      "해결하려는 문제",
                      "왜 지금 해결해야 하는가",
                      "어떻게 해결했는가",
                      "한계와 트레이드오프",
                      "기존 기능에 미치는 영향",
                      "Edge Case와 실패 시나리오",
                      "검토한 대안과 선택 이유",
                      "리뷰 포인트",
                  )
                  .joinToString("\n\n") { heading -> "## $heading\n설명" },
          )
          val commandRunner = RuntimeCommandRunner(root)
          val gateway =
              createDefaultRuntime(
                  currentDirectory = root,
                  environment =
                      mapOf(
                          "WORKFLOW_REPO_ROOT" to root.toString(),
                          "WORKFLOW_STATE_FILE" to statePath.toString(),
                          "GH_USER" to "agent-1",
                          "WORKFLOW_ACTOR_KIND" to "AGENT",
                      ),
                  commandRunner = commandRunner,
              )

          gateway
              .execute(WorkflowCommandRequest.Check)
              .shouldBeInstanceOf<io.springkit.workflow.domain.WorkflowResult.Success<JsonObject>>()

          val result =
              gateway.execute(
                  WorkflowCommandRequest.ReviewOpen(
                      bodyFile = bodyPath.toString(),
                      risk = "normal",
                  )
              )

          val success =
              result.shouldBeInstanceOf<
                  io.springkit.workflow.domain.WorkflowResult.Success<JsonObject>
              >()
          success.data["pull_request"].toString() shouldContain "\"id\":\"1\""

          val stored = WorkflowStateJsonCodec.decode(Files.readString(statePath))
          val pullRequest = requireNotNull(stored.pullRequests["1"])
          val agent = Actor("agent-1", ActorKind.AGENT)
          val openThread =
              ReviewThread(
                  id = "thread-open",
                  level = ReviewLevel.R,
                  comments = listOf(ReviewComment("comment-open", agent, "[Agent] [R] 열린 의견")),
              )
          val resolvedThread =
              ReviewThread(
                  id = "thread-resolved",
                  level = ReviewLevel.C,
                  comments = listOf(ReviewComment("comment-resolved", agent, "[Agent] [C] 해결된 의견")),
                  state = ThreadState.RESOLVED,
              )
          Files.writeString(
              statePath,
              WorkflowStateJsonCodec.encodeToString(
                  stored.copy(
                      pullRequests =
                          stored.pullRequests +
                              (pullRequest.id to
                                  pullRequest.copy(
                                      reviewRevision =
                                          pullRequest.reviewRevision.copy(
                                              threads = listOf(openThread, resolvedThread)
                                          )
                                  ))
                  )
              ),
          )

          val shown =
              gateway.execute(WorkflowCommandRequest.ReviewShow(diff = false, threads = "open"))
          val shownSuccess =
              shown.shouldBeInstanceOf<
                  io.springkit.workflow.domain.WorkflowResult.Success<JsonObject>
              >()
          shownSuccess.data["subtask"].toString() shouldBe "\"sk-101\""
          shownSuccess.data["diff"] shouldBe null
          shownSuccess.data["threads"].toString() shouldContain "thread-open"
          shownSuccess.data["threads"].toString() shouldNotContain "thread-resolved"

          val allThreads =
              gateway.execute(WorkflowCommandRequest.ReviewShow(diff = true, threads = "all"))
          val allThreadsSuccess =
              allThreads.shouldBeInstanceOf<
                  io.springkit.workflow.domain.WorkflowResult.Success<JsonObject>
              >()
          allThreadsSuccess.data["diff"].toString() shouldNotContain "\"identity\":\"head-1\""
          allThreadsSuccess.data["threads"].toString() shouldContain "thread-resolved"

          val reviewBeforeComment =
              WorkflowStateJsonCodec.decode(Files.readString(statePath)).pullRequests.getValue("1")
          reviewBeforeComment.changeRevision.providerRevision shouldBe "head-1"
          val commentResult =
              gateway.execute(
                  WorkflowCommandRequest.ReviewComment(
                      revision = reviewBeforeComment.reviewRevision.id,
                      level = "R",
                      body = "런타임에서 확인했습니다.",
                  )
              )
          commentResult.shouldBeInstanceOf<WorkflowResult.Success<JsonObject>>()
          val reviewAfterComment =
              WorkflowStateJsonCodec.decode(Files.readString(statePath)).pullRequests.getValue("1")
          reviewAfterComment.reviewRevision.id shouldNotBe reviewBeforeComment.reviewRevision.id
          reviewAfterComment.reviewRevision.threads.size shouldBe 3

          val outsideBody = Files.createTempFile("workflow-body-outside-", ".md")
          val invalidResults =
              listOf(
                  gateway.execute(
                      WorkflowCommandRequest.ReviewOpen(
                          bodyFile = outsideBody.toString(),
                          risk = "normal",
                      )
                  ),
                  gateway.execute(
                      WorkflowCommandRequest.ReviewUpdate(
                          revision = pullRequest.reviewRevision.id,
                          bodyFile = outsideBody.toString(),
                      )
                  ),
                  gateway.execute(
                      WorkflowCommandRequest.ReviewComment(
                          revision = pullRequest.reviewRevision.id,
                          level = "R",
                          bodyFile = outsideBody.toString(),
                      )
                  ),
                  gateway.execute(
                      WorkflowCommandRequest.ReviewReply(
                          revision = pullRequest.reviewRevision.id,
                          thread = openThread.id,
                          bodyFile = outsideBody.toString(),
                      )
                  ),
              )

          invalidResults.forEach { result ->
            result
                .shouldBeInstanceOf<io.springkit.workflow.domain.WorkflowResult.Failure>()
                .data
                .code shouldBe io.springkit.workflow.domain.FailureCode.INVALID_ARGUMENT
          }
        }
      }
    })

private class RuntimeCommandRunner(private val repositoryRoot: Path) : CommandRunner {
  private var reviewCommentCreated = false
  private var pullRequestBody = "PR 본문"

  override fun run(command: List<String>, workingDirectory: Path): CommandResult =
      when {
        command == listOf("git", "rev-parse", "--show-toplevel") ->
            CommandResult(0, repositoryRoot.toString() + "\n", "")
        command == listOf("git", "rev-parse", "HEAD") -> CommandResult(0, "head-1\n", "")
        command.firstOrNull() == "git" && command.getOrNull(1) == "status" ->
            CommandResult(0, "", "")
        command.firstOrNull() == "git" && command.getOrNull(1) == "diff" -> CommandResult(0, "", "")
        command.firstOrNull() == "git" && command.getOrNull(1) == "ls-files" ->
            CommandResult(0, "", "")
        command.firstOrNull() == "git" && command.getOrNull(1) == "hash-object" ->
            CommandResult(0, "hash-1\n", "")
        command.firstOrNull() == "gh" &&
            command.getOrNull(1) == "pr" &&
            command.getOrNull(2) == "create" -> {
          val bodyIndex = command.indexOf("--body")
          if (bodyIndex >= 0) {
            pullRequestBody = command.getOrNull(bodyIndex + 1) ?: pullRequestBody
          }
          CommandResult(0, "https://github.com/example/repository/pull/1\n", "")
        }
        command == listOf("gh", "api", "user", "--jq", ".login") ->
            CommandResult(0, "agent-1\n", "")
        command.firstOrNull() == "gh" &&
            command.getOrNull(1) == "pr" &&
            command.getOrNull(2) == "view" &&
            command.contains("--json") &&
            command.getOrNull(command.indexOf("--json") + 1) == "author" ->
            CommandResult(0, """{"author":{"login":"agent-1"}}""", "")
        command.firstOrNull() == "gh" &&
            command.getOrNull(1) == "pr" &&
            command.getOrNull(2) == "view" ->
            CommandResult(
                0,
                pullRequestJson(),
                "",
            )
        command.firstOrNull() == "gh" &&
            command.getOrNull(1) == "api" &&
            command.getOrNull(2) == "graphql" -> CommandResult(0, reviewThreadsJson(), "")
        command.firstOrNull() == "gh" &&
            command.getOrNull(1) == "api" &&
            command.getOrNull(2) == "--method" &&
            command.getOrNull(3) == "POST" -> {
          reviewCommentCreated = true
          CommandResult(0, "{\"id\":123,\"node_id\":\"node-comment-1\"}", "")
        }
        command.firstOrNull() == "gh" && command.getOrNull(1) == "pr" -> CommandResult(0, "[]", "")
        else -> CommandResult(0, "", "")
      }

  private fun pullRequestJson(): String {
    val bodyJson = kotlinx.serialization.json.JsonPrimitive(pullRequestBody).toString()
    val comments =
        if (reviewCommentCreated) {
          """[{"id":"comment-1","databaseId":123,"node_id":"node-comment-1","body":"[Agent] [R] 런타임에서 확인했습니다.","author":{"login":"agent-1"}}]"""
        } else {
          "[]"
        }
    return """
    {"number":1,"title":"[sk-101] 변경","body":$bodyJson,"state":"OPEN","isDraft":true,"baseRefName":"main","headRefName":"sk-101","headRefOid":"head-1","comments":$comments,"reviews":[],"statusCheckRollup":[]}
    """
        .trimIndent()
  }

  private fun reviewThreadsJson(): String {
    return """
    [{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"thread-open","isResolved":false,"comments":{"nodes":[{"id":"comment-open","body":"[Agent] [R] 열린 의견","author":{"login":"agent-1"}}]}},{"id":"thread-resolved","isResolved":true,"comments":{"nodes":[{"id":"comment-resolved","body":"[Agent] [C] 해결된 의견","author":{"login":"agent-1"}}]}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}},"errors":[]}]
    """
        .trimIndent()
  }
}
