package io.springkit.workflow.application

import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskCleanupState
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.WorkflowResult

/*
 * Merge 이후 정리 단계에서 차단된 동작을 기록합니다.
 */
data class PostMergeCleanupBlock(
    val phase: String,
    val target: String,
    val code: String,
    val message: String,
)

/*
 * Merge 이후 정리 요청입니다.
 */
data class PostMergeCleanupRequest(
    val mergedSubTaskId: SubTaskId,
    val expectedStoreRevision: String? = null,
) {
  init {
    require(mergedSubTaskId.isNotBlank()) { "merged subtask id must not be blank" }
  }
}

/*
 * Merge 이후 정리 결과의 상태입니다.
 */
enum class PostMergeCleanupState {
  COMPLETED,
  BLOCKED,
}

/*
 * Merge 이후 정리 결과입니다.
 */
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

/*
 * 성공한 원격 Merge를 유지하면서 Stack, 원격 Branch와 로컬 Worktree를 순서대로 정리합니다.
 */
class PostMergeCleanupUseCase(
    private val gitPort: GitPort,
    private val reviewPort: ReviewPort,
    private val storePort: WorkflowStorePort,
    private val idPort: IdPort? = null,
    private val clockPort: ClockPort? = null,
) {
  /*
   * 기존 Application 호출부의 Store, Git, Review 포트 순서를 지원합니다.
   */
  constructor(
      storePort: WorkflowStorePort,
      gitPort: GitPort,
      reviewPort: ReviewPort,
      idPort: IdPort? = null,
      clockPort: ClockPort? = null,
  ) : this(gitPort, reviewPort, storePort, idPort, clockPort)

  /*
   * Merge 이후 정리를 실행합니다. 정리 실패는 결과의 blocks에 남깁니다.
   */
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
      var currentSnapshot = current
      val candidate =
          currentSnapshot.subTasks.singleOrNull {
            it.id == request.mergedSubTaskId && isMerged(currentSnapshot, it)
          }
      if (candidate == null) {
        blocks +=
            PostMergeCleanupBlock(
                phase = "remote-branch",
                target = request.mergedSubTaskId,
                code = FailureCode.SUBTASK_NOT_FOUND.name,
                message = "정리할 merged SubTask를 찾을 수 없습니다.",
            )
      } else {
        var cleanupState = cleanupState(currentSnapshot, candidate)
        if (cleanupState == SubTaskCleanupState.COMPLETED) {
          return completedResponse(request.mergedSubTaskId, restacked)
        }
        var expectedRevision = cleanupRevision(currentSnapshot, candidate)
        if (cleanupState < SubTaskCleanupState.REMOTE_BRANCH_REMOVED) {
          when (val remoteResult = gitPort.listRemoteBranches(ListRemoteBranchesRequest())) {
            is PortResult.Failure ->
                blocks += remoteResult.toCleanupBlock("remote-branch", candidate.branch)
            is PortResult.Success -> {
              val remoteBranch =
                  remoteResult.value.branches.singleOrNull { it.branch == candidate.branch }
              if (remoteBranch == null) {
                val inspection =
                    inspectLocalArtifact(
                        currentSnapshot,
                        candidate,
                        blocks,
                        allowMissing = expectedRevision != null,
                    )
                if (expectedRevision == null && inspection != null) {
                  val expectedFingerprint = expectedLocalFingerprint(currentSnapshot, candidate)
                  if (
                      expectedFingerprint == null ||
                          inspection.status.fingerprint != expectedFingerprint
                  ) {
                    blocks +=
                        PostMergeCleanupBlock(
                            phase = "worktree",
                            target = inspection.workspaceId,
                            code = "LOCAL_FINGERPRINT_MISMATCH",
                            message = "Worktree fingerprint가 저장된 diff identity와 달라 정리하지 않았습니다.",
                        )
                  } else {
                    expectedRevision = inspection.status.revision
                  }
                } else if (
                    expectedRevision != null &&
                        inspection != null &&
                        inspection.status.revision != expectedRevision
                ) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "worktree",
                          target = inspection.workspaceId,
                          code = "UNPUSHED_COMMIT",
                          message = "Worktree HEAD가 저장된 기대 revision과 달라 정리하지 않았습니다.",
                      )
                }
                if (expectedRevision == null) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "remote-branch",
                          target = candidate.branch,
                          code = "EXPECTED_REVISION_UNAVAILABLE",
                          message = "원격 Branch가 없고 로컬 정리를 검증할 기대 revision을 확인할 수 없습니다.",
                      )
                } else if (blocks.none { it.phase == "worktree" }) {
                  currentSnapshot =
                      persistCleanupRevision(currentSnapshot, candidate, expectedRevision, blocks)
                          ?: currentSnapshot
                  if (blocks.isEmpty()) {
                    currentSnapshot =
                        persistCleanupState(
                            currentSnapshot,
                            candidate,
                            SubTaskCleanupState.REMOTE_BRANCH_REMOVED,
                            blocks,
                        ) ?: currentSnapshot
                    cleanupState = cleanupState(currentSnapshot, candidate)
                  }
                }
              } else if (remoteBranch.revision.isNullOrBlank()) {
                blocks +=
                    PostMergeCleanupBlock(
                        phase = "remote-branch",
                        target = candidate.branch,
                        code = "REMOTE_REVISION_UNAVAILABLE",
                        message = "원격 Branch revision을 확인할 수 없습니다.",
                    )
              } else {
                expectedRevision = remoteBranch.revision
                val providerRevision = providerRevision(currentSnapshot, candidate)
                val checkpointRevision =
                    currentSnapshot.subTasks.firstOrNull { it.id == candidate.id }?.cleanupRevision
                if (
                    (providerRevision != null && providerRevision != expectedRevision) ||
                        (checkpointRevision != null && checkpointRevision != expectedRevision)
                ) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "remote-branch",
                          target = candidate.branch,
                          code = "PROVIDER_REVISION_MISMATCH",
                          message = "PR provider revision과 원격 Branch revision이 달라 정리하지 않았습니다.",
                      )
                }
                val inspection = inspectLocalArtifact(currentSnapshot, candidate, blocks)
                if (inspection != null && inspection.status.revision != expectedRevision) {
                  blocks +=
                      PostMergeCleanupBlock(
                          phase = "worktree",
                          target = inspection.workspaceId,
                          code = "UNPUSHED_COMMIT",
                          message = "Worktree HEAD가 원격 Branch revision과 달라 정리하지 않았습니다.",
                      )
                }
                if (blocks.none { it.phase == "worktree" || it.phase == "remote-branch" }) {
                  currentSnapshot =
                      persistCleanupRevision(currentSnapshot, candidate, expectedRevision, blocks)
                          ?: currentSnapshot
                }
                if (
                    blocks.none {
                      it.phase == "store" || it.phase == "worktree" || it.phase == "remote-branch"
                    }
                ) {
                  when (
                      val removed =
                          gitPort.removeRemoteBranch(
                              RemoveRemoteBranchRequest(
                                  remote = remoteBranch.remote,
                                  branch = remoteBranch.branch,
                                  expectedRevision = remoteBranch.revision,
                              )
                          )
                  ) {
                    is PortResult.Failure ->
                        blocks += removed.toCleanupBlock("remote-branch", candidate.branch)
                    is PortResult.Success -> {
                      removedRemote = listOf(candidate.branch)
                      currentSnapshot =
                          persistCleanupState(
                              currentSnapshot,
                              candidate,
                              SubTaskCleanupState.REMOTE_BRANCH_REMOVED,
                              blocks,
                          ) ?: currentSnapshot
                      cleanupState = cleanupState(currentSnapshot, candidate)
                    }
                  }
                }
              }
            }
          }
        }
        if (blocks.isEmpty() && cleanupState == SubTaskCleanupState.REMOTE_BRANCH_REMOVED) {
          val inspection =
              inspectLocalArtifact(currentSnapshot, candidate, blocks, allowMissing = true)
          if (expectedRevision == null && inspection != null) {
            val expectedFingerprint = expectedLocalFingerprint(currentSnapshot, candidate)
            if (
                expectedFingerprint == null || inspection.status.fingerprint != expectedFingerprint
            ) {
              blocks +=
                  PostMergeCleanupBlock(
                      phase = "worktree",
                      target = inspection.workspaceId,
                      code = "LOCAL_FINGERPRINT_MISMATCH",
                      message = "Worktree fingerprint가 저장된 diff identity와 달라 정리하지 않았습니다.",
                  )
            } else {
              expectedRevision = inspection.status.revision
            }
          } else if (inspection != null && inspection.status.revision != expectedRevision) {
            blocks +=
                PostMergeCleanupBlock(
                    phase = "worktree",
                    target = inspection.workspaceId,
                    code = "UNPUSHED_COMMIT",
                    message = "Worktree HEAD가 저장된 기대 revision과 달라 정리하지 않았습니다.",
                )
          }
          if (expectedRevision == null && blocks.none { it.phase == "worktree" }) {
            blocks +=
                PostMergeCleanupBlock(
                    phase = "worktree",
                    target = candidate.branch,
                    code = "EXPECTED_REVISION_UNAVAILABLE",
                    message = "Worktree가 없고 정리 재시도에 사용할 Git revision이 저장되어 있지 않습니다.",
                )
          }
          if (expectedRevision != null && blocks.none { it.phase == "worktree" }) {
            currentSnapshot =
                persistCleanupRevision(currentSnapshot, candidate, expectedRevision, blocks)
                    ?: currentSnapshot
          }
          if (blocks.none { it.phase == "worktree" }) {
            if (inspection != null) {
              when (
                  val removed =
                      gitPort.removeWorktree(
                          RemoveWorktreeRequest(inspection.workspaceId, inspection.path)
                      )
              ) {
                is PortResult.Failure ->
                    blocks += removed.toCleanupBlock("worktree", inspection.workspaceId)
                is PortResult.Success -> removedWorkspaces = listOf(inspection.workspaceId)
              }
            }
            if (blocks.none { it.phase == "worktree" }) {
              currentSnapshot =
                  persistCleanupState(
                      currentSnapshot,
                      candidate,
                      SubTaskCleanupState.WORKTREE_REMOVED,
                      blocks,
                  ) ?: currentSnapshot
              cleanupState = cleanupState(currentSnapshot, candidate)
            }
          }
        }
        if (blocks.isEmpty() && cleanupState == SubTaskCleanupState.WORKTREE_REMOVED) {
          val localRevision = expectedRevision
          if (localRevision == null) {
            blocks +=
                PostMergeCleanupBlock(
                    phase = "local-branch",
                    target = candidate.branch,
                    code = "EXPECTED_REVISION_UNAVAILABLE",
                    message = "로컬 Branch 정리를 검증할 기대 revision을 확인할 수 없습니다.",
                )
          } else {
            when (
                val removed =
                    gitPort.removeBranch(RemoveBranchRequest(candidate.branch, localRevision))
            ) {
              is PortResult.Failure ->
                  blocks += removed.toCleanupBlock("local-branch", candidate.branch)
              is PortResult.Success -> {
                removedLocal = listOf(candidate.branch)
                currentSnapshot =
                    persistCleanupState(
                        currentSnapshot,
                        candidate,
                        SubTaskCleanupState.LOCAL_BRANCH_REMOVED,
                        blocks,
                    ) ?: currentSnapshot
                cleanupState = cleanupState(currentSnapshot, candidate)
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
    if (mainRevision != null && blocks.isEmpty()) {
      val completionSnapshot = loadSnapshot()
      if (completionSnapshot == null) {
        blocks +=
            PostMergeCleanupBlock(
                phase = "store",
                target = request.mergedSubTaskId,
                code = "STORE_FAILURE",
                message = lastFailure.data.message,
            )
      } else {
        val completionCandidate =
            completionSnapshot.subTasks.singleOrNull { it.id == request.mergedSubTaskId }
        when {
          completionCandidate == null ->
              blocks +=
                  PostMergeCleanupBlock(
                      phase = "store",
                      target = request.mergedSubTaskId,
                      code = FailureCode.SUBTASK_NOT_FOUND.name,
                      message = "완료 저장할 SubTask를 찾을 수 없습니다.",
                  )
          cleanupState(completionSnapshot, completionCandidate) !=
              SubTaskCleanupState.LOCAL_BRANCH_REMOVED ->
              blocks +=
                  PostMergeCleanupBlock(
                      phase = "store",
                      target = request.mergedSubTaskId,
                      code = FailureCode.STATE_CONFLICT.name,
                      message = "SubTask의 cleanup 상태가 완료 저장 단계와 일치하지 않습니다.",
                  )
          else ->
              persistCleanupState(
                  completionSnapshot,
                  completionCandidate,
                  SubTaskCleanupState.COMPLETED,
                  blocks,
              )
        }
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

  /*
   * Merge 이후 정리를 명령 형태로 실행합니다.
   */
  fun cleanup(request: PostMergeCleanupRequest): WorkflowResult<PostMergeCleanupResponse> =
      execute(request)

  /*
   * Merge 이후 정리를 공통 진입점으로 실행합니다.
   */
  fun handle(request: PostMergeCleanupRequest): WorkflowResult<PostMergeCleanupResponse> =
      execute(request)

  private fun inspectLocalArtifact(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      blocks: MutableList<PostMergeCleanupBlock>,
      allowMissing: Boolean = false,
  ): LocalInspection? {
    val storedWorkspace =
        snapshot.subTasks.firstOrNull { it.id == subTask.id }?.workspace
            ?: snapshot.workspaces.firstOrNull { it.subTaskId == subTask.id }
            ?: subTask.workspace
    val workspace = storedWorkspace?.takeIf {
      cleanupState(snapshot, subTask) < SubTaskCleanupState.WORKTREE_REMOVED
    }
    if (workspace == null) return null
    return when (val inspected = gitPort.inspect(GitInspectRequest(workspace.id))) {
      is PortResult.Failure -> {
        if (!allowMissing || inspected.error.code != "WORKSPACE_NOT_FOUND") {
          blocks += inspected.toCleanupBlock("worktree", workspace.id)
        }
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

  private fun cleanupState(snapshot: WorkflowStoreSnapshot, subTask: SubTask) =
      snapshot.subTasks.firstOrNull { it.id == subTask.id }?.cleanupState ?: subTask.cleanupState

  private fun persistCleanupState(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      state: SubTaskCleanupState,
      blocks: MutableList<PostMergeCleanupBlock>,
  ): WorkflowStoreSnapshot? {
    val storedSubTask = snapshot.subTasks.firstOrNull { it.id == subTask.id } ?: subTask
    if (storedSubTask.cleanupState >= state) return snapshot
    val updatedSubTasks =
        snapshot.subTasks.map {
          if (it.id != subTask.id) it else it.copy(cleanupState = state)
        }
    val updated = snapshot.copy(subTasks = updatedSubTasks)
    val transaction =
        StoreTransactionRequest(
            transactionId = "tx-cleanup-${subTask.id}-${snapshot.revision}-${state.name}",
            expectedRevision = snapshot.revision,
            idempotencyKey = "cleanup:${subTask.id}:${snapshot.revision}:${state.name}",
        )
    when (val begun = storePort.begin(transaction)) {
      is PortResult.Failure -> {
        blocks += begun.toCleanupBlock("store", subTask.id)
        return null
      }
      is PortResult.Success -> Unit
    }
    val writtenRevision =
        when (
            val write =
                storePort.write(
                    StoreWriteRequest(transaction.transactionId, snapshot.revision, updated)
                )
        ) {
          is PortResult.Failure -> {
            storePort.rollback(transaction)
            blocks += write.toCleanupBlock("store", subTask.id)
            return null
          }
          is PortResult.Success -> write.value.revision
        }
    when (val committed = storePort.commit(transaction)) {
      is PortResult.Failure -> {
        storePort.rollback(transaction)
        blocks += committed.toCleanupBlock("store", subTask.id)
        return null
      }
      is PortResult.Success -> return updated.copy(revision = writtenRevision)
    }
  }

  private fun persistCleanupRevision(
      snapshot: WorkflowStoreSnapshot,
      subTask: SubTask,
      revision: String,
      blocks: MutableList<PostMergeCleanupBlock>,
  ): WorkflowStoreSnapshot? {
    val storedSubTask = snapshot.subTasks.firstOrNull { it.id == subTask.id } ?: subTask
    val existingRevision = storedSubTask.cleanupRevision
    if (existingRevision == revision) return snapshot
    if (existingRevision != null) {
      blocks +=
          PostMergeCleanupBlock(
              phase = "store",
              target = subTask.id,
              code = "CLEANUP_REVISION_CONFLICT",
              message = "정리 checkpoint의 Git revision이 기존 저장값과 다릅니다.",
          )
      return null
    }
    val updatedSubTasks =
        snapshot.subTasks.map {
          if (it.id != subTask.id) it else it.copy(cleanupRevision = revision)
        }
    val updated = snapshot.copy(subTasks = updatedSubTasks)
    val transaction =
        StoreTransactionRequest(
            transactionId = "tx-cleanup-${subTask.id}-${snapshot.revision}-REVISION",
            expectedRevision = snapshot.revision,
            idempotencyKey = "cleanup:${subTask.id}:${snapshot.revision}:REVISION",
        )
    when (val begun = storePort.begin(transaction)) {
      is PortResult.Failure -> {
        blocks += begun.toCleanupBlock("store", subTask.id)
        return null
      }
      is PortResult.Success -> Unit
    }
    val writtenRevision =
        when (
            val write =
                storePort.write(
                    StoreWriteRequest(transaction.transactionId, snapshot.revision, updated)
                )
        ) {
          is PortResult.Failure -> {
            storePort.rollback(transaction)
            blocks += write.toCleanupBlock("store", subTask.id)
            return null
          }
          is PortResult.Success -> write.value.revision
        }
    when (val committed = storePort.commit(transaction)) {
      is PortResult.Failure -> {
        storePort.rollback(transaction)
        blocks += committed.toCleanupBlock("store", subTask.id)
        return null
      }
      is PortResult.Success -> return updated.copy(revision = writtenRevision)
    }
  }

  private fun completedResponse(
      subTaskId: SubTaskId,
      restacked: List<SubTaskId>,
  ): WorkflowResult.Success<PostMergeCleanupResponse> =
      WorkflowResult.Success(
          PostMergeCleanupResponse(
              mergedSubTaskId = subTaskId,
              state = PostMergeCleanupState.COMPLETED,
              restackedSubTaskIds = restacked,
          )
      )

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

private fun cleanupRevision(
    snapshot: WorkflowStoreSnapshot,
    subTask: SubTask,
): String? =
    snapshot.subTasks.firstOrNull { it.id == subTask.id }?.cleanupRevision
        ?: providerRevision(snapshot, subTask)

private fun providerRevision(
    snapshot: WorkflowStoreSnapshot,
    subTask: SubTask,
): String? =
    subTask.pullRequestId
        ?.let { pullRequestId ->
          snapshot.pullRequests
              .firstOrNull { it.id == pullRequestId }
              ?.changeRevision
              ?.providerRevision
        }
        ?.takeIf { it.isNotBlank() }

private fun expectedLocalFingerprint(
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
