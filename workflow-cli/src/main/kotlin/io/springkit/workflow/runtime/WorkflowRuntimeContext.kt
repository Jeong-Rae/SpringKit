package io.springkit.workflow.runtime

import io.springkit.workflow.application.CurrentActorRequest
import io.springkit.workflow.application.GitInspectRequest
import io.springkit.workflow.application.IdentityPort
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkspaceLookupRequest
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.WorkspaceId
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Path

/** CLI 명령이 현재 실행 위치와 Review 상태를 해석할 때 사용하는 경계입니다. */
interface WorkflowRuntimeContextResolver {
  fun currentWorkspaceContext(): PortResult<WorkflowWorkspaceContext>

  fun currentReview(): PortResult<WorkflowReviewContext>

  fun currentActor(requestId: String): PortResult<Actor>
}

/** 현재 관리 Workspace의 식별자와 경로입니다. */
data class WorkflowWorkspaceContext(
    val id: WorkspaceId,
    val subTaskId: SubTaskId,
    val path: WorkspacePath,
)

/** Review 명령을 Application 요청으로 변환하는 데 필요한 현재 상태입니다. */
data class WorkflowReviewContext(
    val pullRequestId: String,
    val subTaskId: SubTaskId,
    val title: String,
    val base: String,
    val branch: String,
    val reviewRevision: ReviewRevision,
    val changeRevision: ChangeRevision,
)

/** 파일 본문을 읽는 작은 경계입니다. */
fun interface WorkflowBodyReader {
  fun read(path: String): String
}

/** 현재 관리 Worktree 안에 있는 본문 파일의 실제 경로를 해석합니다. */
internal object ManagedWorkspaceBodyPath {
  fun resolve(workspace: WorkspacePath, requestedPath: String): Path {
    val workspacePath = Path.of(workspace.value).toAbsolutePath().normalize().toRealPath()
    val bodyPath = Path.of(requestedPath)
    val normalizedPath =
        if (bodyPath.isAbsolute) bodyPath.normalize()
        else workspacePath.resolve(bodyPath).normalize()
    val canonicalPath = normalizedPath.toRealPath()
    require(canonicalPath.startsWith(workspacePath)) {
      "본문 파일은 현재 관리 Worktree 내부에 있어야 합니다."
    }
    return canonicalPath
  }
}

/** Store와 로컬 Workspace 및 Git 상태를 CLI 현재 문맥으로 변환합니다. */
class StoreWorkflowRuntimeContext(
    private val store: WorkflowStorePort,
    private val workspacePort: io.springkit.workflow.application.WorkspacePort,
    private val gitPort: io.springkit.workflow.application.GitPort,
    private val identityPort: IdentityPort,
) : WorkflowRuntimeContextResolver {
  override fun currentWorkspaceContext(): PortResult<WorkflowWorkspaceContext> =
      currentWorkspace().map {
        WorkflowWorkspaceContext(it.workspace.id, it.workspace.subTaskId, it.workspace.path)
      }

  override fun currentReview(): PortResult<WorkflowReviewContext> {
    val workspace =
        when (val result = currentWorkspace()) {
          is PortResult.Failure -> return result
          is PortResult.Success -> result.value.workspace
        }
    val snapshot =
        when (val result = store.snapshot(StoreSnapshotRequest())) {
          is PortResult.Failure -> return result
          is PortResult.Success -> result.value.snapshot
        }
    val subTask =
        snapshot.subTasks.firstOrNull { it.id == workspace.subTaskId }
            ?: return failure(
                "SUBTASK_NOT_FOUND",
                "현재 Workspace에 연결된 SubTask를 찾을 수 없습니다.",
                workspace.subTaskId,
            )
    val pullRequest = snapshot.pullRequests.firstOrNull { it.subTaskId == subTask.id }
    if (pullRequest != null) {
      return PortResult.Success(
          WorkflowReviewContext(
              pullRequestId = pullRequest.id,
              subTaskId = subTask.id,
              title = pullRequest.title,
              base = pullRequest.base,
              branch = subTask.branch,
              reviewRevision = pullRequest.reviewRevision,
              changeRevision = pullRequest.changeRevision,
          )
      )
    }
    val status =
        when (val result = gitPort.inspect(GitInspectRequest(workspace.id))) {
          is PortResult.Failure -> return result
          is PortResult.Success -> result.value.status
        }
    val reviewNumber = snapshot.sequence.review + 1
    val changeNumber = snapshot.sequence.change + 1
    val parentBranch =
        subTask.requires?.let { required ->
          snapshot.subTasks.firstOrNull { it.id == required }?.branch
        } ?: "main"
    return PortResult.Success(
        WorkflowReviewContext(
            pullRequestId = "new-${subTask.id}",
            subTaskId = subTask.id,
            title = subTask.title,
            base = parentBranch,
            branch = subTask.branch,
            reviewRevision = ReviewRevision("rv-$reviewNumber", reviewNumber, "초기 Review"),
            changeRevision =
                ChangeRevision(
                    id = "cr-$changeNumber",
                    number = changeNumber,
                    diff = Diff(identity = status.fingerprint, files = status.conflicts),
                ),
        )
    )
  }

  override fun currentActor(requestId: String): PortResult<Actor> =
      when (val result = identityPort.currentActor(CurrentActorRequest(requestId))) {
        is PortResult.Failure -> result
        is PortResult.Success -> PortResult.Success(result.value.actor)
      }

  private fun currentWorkspace():
      PortResult<io.springkit.workflow.application.WorkspaceLookupResponse> =
      workspacePort.get(WorkspaceLookupRequest())

  private fun <T, R> PortResult<T>.map(transform: (T) -> R): PortResult<R> =
      when (this) {
        is PortResult.Failure -> this
        is PortResult.Success -> PortResult.Success(transform(value))
      }

  private fun <T> failure(code: String, message: String, target: String): PortResult<T> =
      PortResult.Failure(
          io.springkit.workflow.application.PortError(
              code = code,
              message = message,
              target = target,
          )
      )
}
