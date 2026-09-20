package io.springkit.workflow.adapter.local

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.springkit.workflow.application.AuthorizeRequest
import io.springkit.workflow.application.Capability
import io.springkit.workflow.application.CreateSubTaskRequest
import io.springkit.workflow.application.CreateSubTaskResponse
import io.springkit.workflow.application.CreateWorkspaceRequest
import io.springkit.workflow.application.CurrentActorRequest
import io.springkit.workflow.application.CurrentActorResponse
import io.springkit.workflow.application.DeleteWorkspaceRequest
import io.springkit.workflow.application.NowResponse
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.SubTaskLookupRequest
import io.springkit.workflow.application.TaskLookupRequest
import io.springkit.workflow.application.TaskLookupResponse
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.application.WorkspaceLookupRequest
import io.springkit.workflow.application.WorkspaceLookupResponse
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Files
import java.nio.file.Path

class LocalRuntimeAdapterTest :
    FunSpec({
      context("현재 실행 경로를 Workspace로 해석하면") {
        test("관리 Worktree의 경로이면, 해당 Workspace를 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("workspaces/sk-101"), "sk-101")
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  Path.of("/repo/workspaces/sk-101"),
              )

          val result = adapter.get(WorkspaceLookupRequest())

          val response =
              result.shouldBeInstanceOf<PortResult.Success<WorkspaceLookupResponse>>().value

          response.workspace shouldBe workspace
        }

        test("관리 Worktree의 하위 경로이면, 해당 Workspace를 반환합니다") {
          val workspace =
              Workspace("ws-1", "sk-101", WorkspacePath("/repo/workspaces/sk-101"), "sk-101")
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  Path.of("/repo/workspaces/sk-101/src/main"),
              )

          val result = adapter.get(WorkspaceLookupRequest())

          result
              .shouldBeInstanceOf<PortResult.Success<WorkspaceLookupResponse>>()
              .value
              .workspace shouldBe workspace
        }

        test("관리 Worktree 밖의 경로이면, NOT_MANAGED_WORKTREE 오류를 반환합니다") {
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("workspaces/sk-101"), "sk-101")
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  Path.of("/repo"),
              )

          val failure =
              adapter.get(WorkspaceLookupRequest()).shouldBeInstanceOf<PortResult.Failure>()

          failure.error.code shouldBe "NOT_MANAGED_WORKTREE"
        }

        test("관리되지 않은 Workspace를 삭제하려 하면, NOT_MANAGED_WORKTREE 오류를 반환합니다") {
          val workspace =
              Workspace(
                  "ws-1",
                  "sk-101",
                  WorkspacePath("/repo/workspaces/sk-101"),
                  "sk-101",
                  managed = false,
              )
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  Path.of("/repo"),
              )

          val failure =
              adapter
                  .delete(
                      io.springkit.workflow.application.DeleteWorkspaceRequest("ws-1", "sk-101")
                  )
                  .shouldBeInstanceOf<PortResult.Failure>()

          failure.error.code shouldBe "NOT_MANAGED_WORKTREE"
        }
      }

      context("관리 root 경계를 검사하면") {
        test("상대 Workspace 경로를 관리 root 기준으로 해석합니다") {
          val root = Files.createTempDirectory("workflow-workspace-root-")
          val current = root.resolve("workspaces/sk-101/src/main")
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("workspaces/sk-101"), "sk-101")
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  current,
                  root,
              )

          adapter.get(WorkspaceLookupRequest()).shouldBeInstanceOf<PortResult.Success<*>>()
        }

        test("절대 경로가 관리 root 밖이면 Workspace 생성을 거부합니다") {
          val root = Files.createTempDirectory("workflow-workspace-root-")
          val outside = Files.createTempDirectory("workflow-workspace-outside-")
          val adapter = LocalWorkspaceAdapter(WorkflowStoreSnapshot("1"), root, root)

          val result =
              adapter.create(
                  CreateWorkspaceRequest(
                      workspaceId = "ws-1",
                      subTaskId = "sk-101",
                      path = WorkspacePath(outside.toString()),
                      branch = "feature/sk-101",
                      baseRevision = "main",
                  )
              )

          result.shouldBeInstanceOf<PortResult.Failure>().error.code shouldBe
              "WORKSPACE_PATH_INVALID"
        }

        test("`..`으로 관리 root 밖으로 탈출한 경로의 Workspace 삭제를 거부합니다") {
          val root = Files.createTempDirectory("workflow-workspace-root-")
          val workspace = Workspace("ws-1", "sk-101", WorkspacePath("../outside"), "sk-101")
          val adapter =
              LocalWorkspaceAdapter(
                  WorkflowStoreSnapshot("1", workspaces = listOf(workspace)),
                  root,
                  root,
              )

          val result = adapter.delete(DeleteWorkspaceRequest("ws-1", "sk-101"))

          result.shouldBeInstanceOf<PortResult.Failure>().error.code shouldBe
              "WORKSPACE_PATH_INVALID"
        }

        test("외부를 가리키는 심볼릭 링크 경로의 Workspace 조회를 거부합니다") {
          val root = Files.createTempDirectory("workflow-workspace-root-")
          val outside = Files.createTempDirectory("workflow-workspace-outside-")
          Files.createSymbolicLink(root.resolve("link"), outside)
          val adapter = LocalWorkspaceAdapter(WorkflowStoreSnapshot("1"), root, root)

          val result = adapter.get(WorkspaceLookupRequest(path = WorkspacePath("link")))

          result.shouldBeInstanceOf<PortResult.Failure>().error.code shouldBe
              "WORKSPACE_PATH_INVALID"
        }

        test("외부를 가리키는 심볼릭 링크 아래의 미존재 자식 경로도 거부합니다") {
          val root = Files.createTempDirectory("workflow-workspace-root-")
          val outside = Files.createTempDirectory("workflow-workspace-outside-")
          Files.createSymbolicLink(root.resolve("link"), outside)
          val adapter = LocalWorkspaceAdapter(WorkflowStoreSnapshot("1"), root, root)

          val result =
              adapter.get(WorkspaceLookupRequest(path = WorkspacePath("link/nonexistent/body.md")))

          result.shouldBeInstanceOf<PortResult.Failure>().error.code shouldBe
              "WORKSPACE_PATH_INVALID"
        }
      }

      context("snapshot에서 외부 Task를 조회하면") {
        test("외부 Task ID로 조회하면, 일치하는 Task를 반환합니다") {
          val task = Task("task-1", ExternalTaskId("TASK-1"), "작업")
          val adapter = SnapshotTaskAdapter(WorkflowStoreSnapshot("1", tasks = listOf(task)))

          val result = adapter.get(TaskLookupRequest(externalId = ExternalTaskId("TASK-1")))

          val response = result.shouldBeInstanceOf<PortResult.Success<TaskLookupResponse>>().value

          response.task shouldBe task
        }

        test("존재하지 않는 SubTask ID로 조회하면, SUBTASK_NOT_FOUND 오류를 반환합니다") {
          val adapter = SnapshotTaskAdapter(WorkflowStoreSnapshot("1"))

          val failure =
              adapter
                  .getSubTask(SubTaskLookupRequest("sk-404"))
                  .shouldBeInstanceOf<PortResult.Failure>()

          failure.error.code shouldBe "SUBTASK_NOT_FOUND"
        }
      }

      context("외부 Task 제공자 없이 시작하면") {
        test("외부 Task ID를 조회하면, 로컬 Task를 임시로 구체화합니다") {
          val adapter = SnapshotTaskAdapter(WorkflowStoreSnapshot("1"))

          val response =
              adapter
                  .get(TaskLookupRequest(externalId = ExternalTaskId("TASK-1")))
                  .shouldBeInstanceOf<PortResult.Success<TaskLookupResponse>>()
                  .value

          response.task.id shouldBe "TASK-1"
          response.task.title shouldBe "TASK-1"
          response.task.externalId shouldBe ExternalTaskId("TASK-1")
        }

        test("외부 Task의 SubTask를 생성하면, 임시 Task와 발급된 ID를 함께 반환합니다") {
          val adapter = SnapshotTaskAdapter(WorkflowStoreSnapshot("1"))

          val response =
              adapter
                  .createSubTask(
                      CreateSubTaskRequest(
                          externalTaskId = ExternalTaskId("TASK-1"),
                          subTaskId = "sk-101",
                          title = "변경",
                          requestId = "request-1",
                      ),
                  )
                  .shouldBeInstanceOf<PortResult.Success<CreateSubTaskResponse>>()
                  .value

          response.task.id shouldBe "TASK-1"
          response.subTask.id shouldBe "sk-101"
        }
      }

      context("외부 Task의 SubTask 생성을 요청하면") {
        test("snapshot에 Task가 있으면, 저장할 SubTask와 변경 영수증을 반환합니다") {
          val existing = SubTask("sk-100", "task-1", "기존")
          val task =
              Task("task-1", ExternalTaskId("TASK-1"), "작업", subTaskIds = listOf(existing.id))
          val adapter =
              SnapshotTaskAdapter(
                  WorkflowStoreSnapshot(
                      "1",
                      tasks = listOf(task),
                      subTasks = listOf(existing),
                  )
              )

          val result =
              adapter.createSubTask(
                  CreateSubTaskRequest(
                      externalTaskId = ExternalTaskId("TASK-1"),
                      subTaskId = "sk-101",
                      title = "변경",
                      requestId = "request-1",
                  )
              )
          val response =
              result.shouldBeInstanceOf<PortResult.Success<CreateSubTaskResponse>>().value

          response.subTask.taskId shouldBe task.id
          response.subTask.title shouldBe "변경"
          response.change.operation shouldBe "create-subtask"
        }
      }

      context("시스템 시계를 조회하면") {
        test("주입한 epoch 값을 반환합니다") {
          val result = SystemClockAdapter { 1_723_456_789L }.now()

          val response = result.shouldBeInstanceOf<PortResult.Success<NowResponse>>().value

          response.epochMillis shouldBe 1_723_456_789L
        }
      }

      context("현재 principal과 사람 Gate를 확인하면") {
        test("gh 인증 사용자를 조회하면, 기본적으로 AGENT actor로 반환합니다") {
          val runner = RecordingCommandRunner(CommandResult(0, "octocat\n", ""))
          val adapter =
              EnvironmentIdentityAdapter(
                  commandRunner = runner,
                  workingDirectory = Path.of("/repo"),
                  environment = mapOf("USER" to "runner"),
              )

          val result = adapter.currentActor(CurrentActorRequest("request-1"))
          val actor =
              result.shouldBeInstanceOf<PortResult.Success<CurrentActorResponse>>().value.actor

          actor shouldBe Actor("octocat", ActorKind.AGENT, "octocat")
          runner.command shouldBe listOf("gh", "api", "user", "--jq", ".login")
        }

        test("환경 변수만 HUMAN으로 지정해도 gh principal을 HUMAN actor로 승격하지 않습니다") {
          val runner = RecordingCommandRunner(CommandResult(0, "octocat\n", ""))
          val adapter =
              EnvironmentIdentityAdapter(
                  commandRunner = runner,
                  environment = mapOf("WORKFLOW_ACTOR_KIND" to "HUMAN"),
              )

          val result = adapter.currentActor()

          result
              .shouldBeInstanceOf<PortResult.Success<CurrentActorResponse>>()
              .value
              .actor
              .kind shouldBe ActorKind.AGENT
        }

        test("명시적 human allowlist에 있는 gh principal만 HUMAN actor로 반환합니다") {
          val runner = RecordingCommandRunner(CommandResult(0, "octocat\n", ""))
          val adapter =
              EnvironmentIdentityAdapter(
                  commandRunner = runner,
                  environment = mapOf("WORKFLOW_ACTOR_KIND" to "HUMAN"),
                  humanActorIds = setOf("octocat"),
              )

          val result = adapter.currentActor()

          result
              .shouldBeInstanceOf<PortResult.Success<CurrentActorResponse>>()
              .value
              .actor
              .kind shouldBe ActorKind.HUMAN
        }

        test("자동화 principal이 사람 Gate를 요청하면, 권한을 거부합니다") {
          val adapter =
              EnvironmentIdentityAdapter(
                  environment = mapOf("CI" to "true", "GITHUB_ACTOR" to "workflow-bot")
              )

          val result =
              adapter.authorize(
                  AuthorizeRequest(
                      Actor("workflow-bot", ActorKind.WORKFLOW),
                      Capability.APPROVE,
                      "pr-1",
                  )
              )
          val response =
              result
                  .shouldBeInstanceOf<
                      PortResult.Success<io.springkit.workflow.application.AuthorizeResponse>
                  >()
                  .value

          response.allowed shouldBe false
          response.reason shouldContain "HUMAN"
        }

        test("인증된 gh principal과 다른 HUMAN actor를 전달하면, 사람 Gate를 거부합니다") {
          val runner = RecordingCommandRunner(CommandResult(0, "octocat\n", ""))
          val adapter =
              EnvironmentIdentityAdapter(
                  commandRunner = runner,
                  environment = mapOf("USER" to "runner"),
                  humanActorIds = setOf("trusted-human"),
              )

          val result =
              adapter.authorize(
                  AuthorizeRequest(
                      Actor("trusted-human", ActorKind.HUMAN),
                      Capability.APPROVE,
                      "pr-1",
                  )
              )
          val response =
              result
                  .shouldBeInstanceOf<
                      PortResult.Success<io.springkit.workflow.application.AuthorizeResponse>
                  >()
                  .value

          response.allowed shouldBe false
          response.reason shouldContain "principal"
        }
      }
    })

private class RecordingCommandRunner(private val next: CommandResult) : CommandRunner {
  var command: List<String>? = null

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    this.command = command
    return next
  }
}
