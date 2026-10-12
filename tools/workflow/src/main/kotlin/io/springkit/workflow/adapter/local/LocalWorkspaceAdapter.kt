package io.springkit.workflow.adapter.local

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.CreateWorkspaceRequest
import io.springkit.workflow.application.CreateWorkspaceResponse
import io.springkit.workflow.application.DeleteWorkspaceRequest
import io.springkit.workflow.application.DeleteWorkspaceResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.application.WorkspaceLookupRequest
import io.springkit.workflow.application.WorkspaceLookupResponse
import io.springkit.workflow.application.WorkspacePort
import io.springkit.workflow.common.ManagedPathResolver
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Path

/*
 * 현재 실행 경로를 Store의 관리 Worktree로 해석하는 로컬 [WorkspacePort] 구현입니다.
 */
class LocalWorkspaceAdapter(
    private val snapshotProvider: () -> WorkflowStoreSnapshot,
    private val currentPathProvider: () -> Path = ::defaultCurrentPath,
    managedRoot: Path? = null,
) : WorkspacePort {
  private val managedRoot: Path? = managedRoot?.let(ManagedPathResolver::canonicalize)

  /*
   * Store Port를 사용해 현재 snapshot을 읽는 Workspace adapter를 만듭니다.
   */
  constructor(
      storePort: WorkflowStorePort,
      currentPathProvider: () -> Path = ::defaultCurrentPath,
      managedRoot: Path? = null,
  ) : this(
      {
        when (val result = storePort.snapshot(StoreSnapshotRequest())) {
          is PortResult.Success -> result.value.snapshot
          is PortResult.Failure -> throw WorkspaceSnapshotException(result.error)
        }
      },
      currentPathProvider,
      managedRoot,
  )

  /*
   * 고정 snapshot과 실행 경로로 테스트 가능한 Workspace adapter를 만듭니다.
   */
  constructor(
      snapshot: WorkflowStoreSnapshot,
      currentPath: Path,
      managedRoot: Path? = null,
  ) : this({ snapshot }, { currentPath }, managedRoot)

  override fun get(request: WorkspaceLookupRequest): PortResult<WorkspaceLookupResponse> {
    val snapshot = readSnapshot() ?: return lastFailure
    val currentPath =
        safeResolve(currentPathProvider()) ?: return invalidPath(currentPathProvider())
    request.path?.let { path ->
      if (safeResolve(path) == null) return invalidPath(path.value)
    }
    val workspace =
        snapshot.workspaces.firstOrNull { candidate ->
          matches(candidate, request) &&
              (request.workspaceId != null ||
                  request.subTaskId != null ||
                  request.path != null ||
                  matchesCurrentPath(candidate.path, currentPath))
        }
            ?: return failure(
                code =
                    if (isCurrentLookup(request)) "NOT_MANAGED_WORKTREE" else "WORKSPACE_NOT_FOUND",
                message =
                    if (isCurrentLookup(request)) {
                      "현재 경로가 관리되는 Worktree에 속하지 않습니다: $currentPath"
                    } else {
                      "Workspace를 찾을 수 없습니다."
                    },
                target = request.path?.value ?: request.workspaceId ?: request.subTaskId,
            )
    if (!workspace.managed) {
      return failure(
          code = "NOT_MANAGED_WORKTREE",
          message = "Workspace가 관리되는 Worktree가 아닙니다: ${workspace.id}",
          target = workspace.id,
      )
    }
    return PortResult.Success(WorkspaceLookupResponse(workspace))
  }

  override fun create(request: CreateWorkspaceRequest): PortResult<CreateWorkspaceResponse> {
    if (safeResolve(request.path) == null) return invalidPath(request.path.value)
    val workspace =
        Workspace(
            id = request.workspaceId,
            subTaskId = request.subTaskId,
            path = request.path,
            branch = request.branch,
            managed = true,
        )
    return PortResult.Success(
        CreateWorkspaceResponse(
            workspace = workspace,
            change = ChangeReceipt("workspace-create-${request.workspaceId}", "create-workspace"),
        )
    )
  }

  override fun delete(request: DeleteWorkspaceRequest): PortResult<DeleteWorkspaceResponse> {
    val snapshot = readSnapshot() ?: return lastFailure
    val workspace = snapshot.workspaces.firstOrNull { it.id == request.workspaceId }
    if (workspace == null || workspace.subTaskId != request.subTaskId) {
      return failure(
          code = "WORKSPACE_NOT_FOUND",
          message = "삭제할 Workspace를 찾을 수 없습니다: ${request.workspaceId}",
          target = request.workspaceId,
      )
    }
    if (!workspace.managed) {
      return failure(
          code = "NOT_MANAGED_WORKTREE",
          message = "Workspace가 관리되는 Worktree가 아닙니다: ${workspace.id}",
          target = workspace.id,
      )
    }
    if (safeResolve(workspace.path) == null) return invalidPath(workspace.path.value)
    return PortResult.Success(
        DeleteWorkspaceResponse(
            workspaceId = request.workspaceId,
            change = ChangeReceipt("workspace-delete-${request.workspaceId}", "delete-workspace"),
        )
    )
  }

  private var lastFailure: PortResult.Failure =
      PortResult.Failure(
          PortError("WORKSPACE_SNAPSHOT_UNAVAILABLE", "Workflow Store snapshot을 읽을 수 없습니다.")
      )

  private fun readSnapshot(): WorkflowStoreSnapshot? =
      try {
        snapshotProvider()
      } catch (failure: WorkspaceSnapshotException) {
        lastFailure = PortResult.Failure(failure.error)
        null
      } catch (failure: Exception) {
        lastFailure =
            PortResult.Failure(
                PortError(
                    code = "WORKSPACE_SNAPSHOT_UNAVAILABLE",
                    message = "Workflow Store snapshot을 읽을 수 없습니다: ${failure.message}",
                    retryable = true,
                )
            )
        null
      }

  private fun matches(workspace: Workspace, request: WorkspaceLookupRequest): Boolean =
      (request.workspaceId == null || request.workspaceId == workspace.id) &&
          (request.subTaskId == null || request.subTaskId == workspace.subTaskId) &&
          (request.path == null || pathsMatch(request.path, workspace.path))

  private fun isCurrentLookup(request: WorkspaceLookupRequest): Boolean =
      request.workspaceId == null && request.subTaskId == null && request.path == null

  private fun resolve(path: WorkspacePath): Path {
    val candidate = Path.of(path.value)
    if (candidate.isAbsolute) return candidate
    val root = managedRoot ?: normalize(currentPathProvider())
    return root.resolve(candidate)
  }

  private fun pathsMatch(left: WorkspacePath, right: WorkspacePath): Boolean =
      if (
          managedRoot == null && !Path.of(left.value).isAbsolute && !Path.of(right.value).isAbsolute
      ) {
        Path.of(left.value).normalize() == Path.of(right.value).normalize()
      } else {
        safeResolve(left)?.let { leftPath ->
          safeResolve(right)?.let { rightPath -> leftPath == rightPath }
        } ?: false
      }

  private fun matchesCurrentPath(workspacePath: WorkspacePath, currentPath: Path): Boolean {
    val rawPath = Path.of(workspacePath.value)
    if (managedRoot == null && !rawPath.isAbsolute) {
      return currentPath.normalize().endsWith(rawPath.normalize())
    }
    val candidate = safeResolve(workspacePath) ?: return false
    return currentPath == candidate || currentPath.startsWith(candidate)
  }

  private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

  private fun safeResolve(path: WorkspacePath): Path? = safeResolve(resolve(path))

  private fun safeResolve(path: Path): Path? {
    val root = managedRoot ?: return ManagedPathResolver.canonicalize(path)
    return ManagedPathResolver.resolveWithin(root, path)
  }

  private fun invalidPath(path: Any): PortResult.Failure =
      PortResult.Failure(
          PortError(
              code = "WORKSPACE_PATH_INVALID",
              message = "Workspace 경로가 관리 root 밖에 있거나 심볼릭 링크로 탈출합니다: $path",
              target = path.toString(),
          )
      )

  private fun <T> failure(
      code: String,
      message: String,
      target: String? = null,
  ): PortResult<T> = PortResult.Failure(PortError(code, message, target = target))

  private class WorkspaceSnapshotException(val error: PortError) : RuntimeException(error.message)
}

private fun defaultCurrentPath(): Path =
    Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

/*
 * 현재 실행 경로를 조회하는 용도를 드러내는 호환 별칭입니다.
 */
typealias CurrentWorkspaceAdapter = LocalWorkspaceAdapter
