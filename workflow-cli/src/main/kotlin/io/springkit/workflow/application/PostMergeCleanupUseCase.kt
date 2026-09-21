package io.springkit.workflow.application

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult

/** Merge 이후 정리 단계에서 차단된 동작을 기록합니다. */
data class PostMergeCleanupBlock(
    val phase: String,
    val target: String,
    val code: String,
    val message: String,
)

/** Merge 이후 정리 요청입니다. */
data class PostMergeCleanupRequest(
    val mergedSubTaskId: SubTaskId,
    val expectedStoreRevision: String? = null,
) {
  init {
    require(mergedSubTaskId.isNotBlank()) { "merged subtask id must not be blank" }
  }
}

/** Merge 이후 정리 결과의 상태입니다. */
enum class PostMergeCleanupState {
  COMPLETED,
  BLOCKED,
}

/** Merge 이후 정리 결과입니다. */
data class PostMergeCleanupResponse(
    val mergedSubTaskId: SubTaskId,
    val state: PostMergeCleanupState,
    val mainRevision: String? = null,
    val restackedSubTaskIds: List<SubTaskId> = emptyList(),
    val removedRemoteBranches: List<String> = emptyList(),
    val removedWorkspaces: List<String> = emptyList(),
    val removedLocalBranches: List<String> = emptyList(),
    val blocks: List<PostMergeCleanupBlock> = emptyList(),
)

/** 성공한 원격 Merge를 유지하면서 Stack, 원격 Branch와 로컬 Worktree를 순서대로 정리합니다. */
class PostMergeCleanupUseCase(
    private val gitPort: GitPort,
    private val reviewPort: ReviewPort,
    private val storePort: WorkflowStorePort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
) {
  /** 기존 Application 호출부의 Store, Git, Review 포트 순서를 지원합니다. */
  constructor(
      storePort: WorkflowStorePort,
      gitPort: GitPort,
      reviewPort: ReviewPort,
      idPort: IdPort? = null,
      clockPort: ClockPort? = null,
  ) : this(gitPort, reviewPort, storePort, idPort, clockPort)

  /** Merge 이후 정리를 실행합니다. 정리 실패는 결과의 blocks에 남깁니다. */
  fun execute(request: PostMergeCleanupRequest): WorkflowResult<PostMergeCleanupResponse> {
    val initial = loadSnapshot() ?: return lastFailure
    if (
        request.expectedStoreRevision != null && request.expectedStoreRevision != initial.revision
    ) {
      return failure(
          FailureCode.STALE_REVISION,
          "workflow store revision is stale",
          request.mergedSubTaskId,
      )
    }
    val merged = initial.subTasks.firstOrNull { it.id == request.mergedSubTaskId }
    if (merged == null) {
      return failure(
          FailureCode.SUBTASK_NOT_FOUND,
          "merged subtask was not found",
          request.mergedSubTaskId,
      )
    }
    if (!isMerged(initial, merged)) {
      return failure(
          FailureCode.STATE_CONFLICT,
          "subtask is not merged",
          request.mergedSubTaskId,
      )
    }

    val blocks = mutableListOf<PostMergeCleanupBlock>()
    val restacked = mutableListOf<SubTaskId>()
    val children =
        initial.subTasks.filter {
          it.state != SubTaskState.MERGED && it.requires == request.mergedSubTaskId
        }
    val stackSync =
        StackSyncUseCases(
            gitPort,
            reviewPort,
            storePort,
            idPort,
            null,
            clockPort,
        )
    children.forEach { child ->
      when (val result = stackSync.sync(SyncRequest(child.id))) {
        is WorkflowResult.Success -> restacked += child.id
        is WorkflowResult.Failure ->
            blocks +=
                result.data.toCleanupBlocks(
                    phase = "restack",
                    fallbackTarget = child.id,
                )
      }
    }

    val current = loadSnapshot()
    if (current == null) {
      blocks +=
          PostMergeCleanupBlock(
              phase = "remote-branch",
              target = request.mergedSubTaskId,
              code = "STORE_FAILURE",
              message = lastFailure.data.message,
          )
    }
    var removedRemote = emptyList<String>()
    var removedWorkspaces = emptyList<String>()
    var removedLocal = emptyList<String>()
    if (blocks.none { it.phase == "restack" } && current != null) {
      val currentSnapshot = current
      val candidate =
          currentSnapshot.subTasks.singleOrNull {
            it.id == request.mergedSubTaskId && isMerged(currentSnapshot, it)
          }
      val remoteResult = gitPort.listRemoteBranches(ListRemoteBranchesRequest())
      when (remoteResult) {
        is PortResult.Failure ->
            blocks += remoteResult.toCleanupBlock("remote-branch", request.mergedSubTaskId)
        is PortResult.Success -> {
          if (candidate == null) {
            blocks +=
                PostMergeCleanupBlock(
                    phase = "remote-branch",
                    target = request.mergedSubTaskId,
                    code = FailureCode.SUBTASK_NOT_FOUND.name,
                    message = "정리할 merged SubTask를 찾을 수 없습니다.",
                )
          } else {
            val remoteBranch =
                remoteResult.value.branches.singleOrNull { it.branch == candidate.branch }
            when {
              remoteBranch == null -> {
                val expectedRevision = expectedLocalRevision(currentSnapshot, candidate)
                if (expectedRevision == null) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "remote-branch",
                          target = candidate.branch,
                          code = "EXPECTED_REVISION_UNAVAILABLE",
                          message = "원격 Branch가 없고 로컬 정리를 검증할 기대 revision을 확인할 수 없습니다.",
                      )
                } else {
                  val hasWorkspace =
                      candidate.workspace != null ||
                          currentSnapshot.workspaces.any { it.subTaskId == candidate.id }
                  val inspection = inspectLocalArtifact(currentSnapshot, candidate, blocks)
                  if (inspection != null && inspection.status.revision != expectedRevision) {
                    blocks +=
                        PostMergeCleanupBlock(
                            phase = "worktree",
                            target = inspection.workspaceId,
                            code = "UNPUSHED_COMMIT",
                            message = "Worktree HEAD가 저장된 기대 revision과 달라 정리하지 않았습니다.",
                        )
                  }
                  if (
                      (!hasWorkspace || inspection != null) &&
                          blocks.none { it.phase == "worktree" }
                  ) {
                    val local =
                        removeLocalArtifacts(
                            candidate,
                            inspection,
                            expectedRevision,
                            blocks,
                        )
                    removedWorkspaces = local.workspaces
                    removedLocal = local.branches
                  }
                }
              }
              remoteBranch.revision.isNullOrBlank() ->
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "remote-branch",
                          target = candidate.branch,
                          code = "REMOTE_REVISION_UNAVAILABLE",
                          message = "원격 Branch revision을 확인할 수 없습니다.",
                      )
              else -> {
                val hasWorkspace =
                    candidate.workspace != null ||
                        currentSnapshot.workspaces.any { it.subTaskId == candidate.id }
                val inspection = inspectLocalArtifact(currentSnapshot, candidate, blocks)
                if (inspection != null && inspection.status.revision != remoteBranch.revision) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "worktree",
                          target = inspection.workspaceId,
                          code = "UNPUSHED_COMMIT",
                          message = "Worktree HEAD가 원격 Branch revision과 달라 정리하지 않았습니다.",
                      )
                }
                if (
                    !(hasWorkspace && inspection == null) &&
                        !(inspection != null && blocks.any { it.phase == "worktree" })
                ) {
                  when (
                      val result =
                          gitPort.removeRemoteBranch(
                              RemoveRemoteBranchRequest(
                                  remote = remoteBranch.remote,
                                  branch = remoteBranch.branch,
                                  expectedRevision = remoteBranch.revision,
                              )
                          )
                  ) {
                    is PortResult.Failure ->
                        blocks += result.toCleanupBlock("remote-branch", remoteBranch.branch)
                    is PortResult.Success -> {
                      removedRemote = listOf(remoteBranch.branch)
                      val local =
                          removeLocalArtifacts(
                              candidate,
                              inspection,
                              remoteBranch.revision,
                              blocks,
                          )
                      removedWorkspaces = local.workspaces
                      removedLocal = local.branches
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    val refreshed = gitPort.refreshMain(MainRevisionRequest())
    val mainRevision =
        when (refreshed) {
          is PortResult.Success -> refreshed.value.revision
          is PortResult.Failure -> {
            blocks += refreshed.toCleanupBlock("refresh-main", "origin/main")
            null
          }
        }
    val state =
        if (blocks.isEmpty()) PostMergeCleanupState.COMPLETED else PostMergeCleanupState.BLOCKED
    val response =
        PostMergeCleanupResponse(
            mergedSubTaskId = request.mergedSubTaskId,
            state = state,
            mainRevision = mainRevision,
            restackedSubTaskIds = restacked,
            removedRemoteBranches = removedRemote,
            removedWorkspaces = removedWorkspaces,
            removedLocalBranches = removedLocal,
            blocks = blocks,
        )
    return WorkflowResult.Success(
        response,
        next =
            if (blocks.isEmpty()) {
              emptyList()
            } else {
              listOf(NextAction(ActorKind.WORKFLOW, "retry_cleanup"))
            },
    )
  }

  /** Merge 이후 정리를 명령 형태로 실행합니다. */
  fun cleanup(request: PostMergeCleanupRequest): WorkflowResult<PostMergeCleanupResponse> =
      execute(request)

  /** Merge 이후 정리를 공통 진입점으로 실행합니다. */
  fun handle(request: PostMergeCleanupRequest): WorkflowResult<PostMergeCleanupResponse> =
      execute(request)

  private fun inspectLocalArtifact(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      blocks: MutableList<PostMergeCleanupBlock>,
  ): LocalInspection? {
    val workspace =
        subTask.workspace ?: snapshot.workspaces.firstOrNull { it.subTaskId == subTask.id }
    if (workspace == null) return null
    return when (val inspected = gitPort.inspect(GitInspectRequest(workspace.id))) {
      is PortResult.Failure -> {
        blocks += inspected.toCleanupBlock("worktree", workspace.id)
        null
      }
      is PortResult.Success -> {
        if (inspected.value.status.dirty) {
          blocks +=
              PostMergeCleanupBlock(
                  phase = "worktree",
                  target = workspace.id,
                  code = FailureCode.WORKTREE_DIRTY.name,
                  message = "게시되지 않은 변경이 있는 Worktree를 제거하지 않았습니다.",
              )
          null
        } else {
          LocalInspection(workspace.id, workspace.path, inspected.value.status)
        }
      }
    }
  }

  private fun removeLocalArtifacts(
      candidate: SubTask,
      inspection: LocalInspection?,
      expectedBranchRevision: String,
      blocks: MutableList<PostMergeCleanupBlock>,
  ): LocalCleanup {
    val workspaces = mutableListOf<String>()
    val branches = mutableListOf<String>()
    if (inspection != null) {
      when (
          val removed =
              gitPort.removeWorktree(RemoveWorktreeRequest(inspection.workspaceId, inspection.path))
      ) {
        is PortResult.Failure ->
            blocks += removed.toCleanupBlock("worktree", inspection.workspaceId)
        is PortResult.Success -> workspaces += inspection.workspaceId
      }
    }
    if (blocks.none { it.phase == "worktree" }) {
      when (
          val removed =
              gitPort.removeBranch(
                  RemoveBranchRequest(
                      branch = candidate.branch,
                      expectedRevision = expectedBranchRevision,
                  )
              )
      ) {
        is PortResult.Success -> branches += candidate.branch
        is PortResult.Failure -> blocks += removed.toCleanupBlock("local-branch", candidate.branch)
      }
    }
    return LocalCleanup(workspaces, branches)
  }

  private fun isMerged(snapshot: WorkflowStoreSnapshot, subTask: SubTask): Boolean =
      subTask.state == SubTaskState.MERGED ||
          snapshot.integrations.any {
            it.subTaskId == subTask.id && it.state == IntegrationState.MERGED
          }

  private fun loadSnapshot(): WorkflowStoreSnapshot? =
      when (val result = storePort.snapshot(StoreSnapshotRequest(StoreScope.ALL))) {
        is PortResult.Success -> result.value.snapshot
        is PortResult.Failure -> {
          lastFailure = result.toWorkflowFailure(FailureCode.STORE_FAILURE)
          null
        }
      }

  private fun failure(
      code: FailureCode,
      message: String,
      target: String,
  ): WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(
              code = code,
              message = message,
              blockedBy = listOf(BlockedBy(code.name, message, target)),
          )
      )

  private data class LocalCleanup(
      val workspaces: List<String>,
      val branches: List<String>,
  )

  private data class LocalInspection(
      val workspaceId: String,
      val path: io.springkit.workflow.domain.WorkspacePath,
      val status: GitStatus,
  )

  private var lastFailure: WorkflowResult.Failure =
      WorkflowResult.Failure(
          FailureData(FailureCode.STORE_FAILURE, "workflow store operation failed")
      )
}

private fun FailureData.toCleanupBlocks(
    phase: String,
    fallbackTarget: String,
): List<PostMergeCleanupBlock> =
    if (blockedBy.isEmpty()) {
      listOf(PostMergeCleanupBlock(phase, fallbackTarget, code.name, message))
    } else {
      blockedBy.map {
        PostMergeCleanupBlock(phase, it.target ?: fallbackTarget, it.code, it.message)
      }
    }

private fun PortResult.Failure.toCleanupBlock(
    phase: String,
    fallbackTarget: String,
): PostMergeCleanupBlock =
    PostMergeCleanupBlock(phase, error.target ?: fallbackTarget, error.code, error.message)

private fun expectedLocalRevision(
    snapshot: WorkflowStoreSnapshot,
    subTask: SubTask,
): String? =
    subTask.pullRequestId
        ?.let { pullRequestId ->
          snapshot.pullRequests
              .firstOrNull { it.id == pullRequestId }
              ?.changeRevision
              ?.diff
              ?.identity
        }
        ?.takeIf { it.isNotBlank() }
