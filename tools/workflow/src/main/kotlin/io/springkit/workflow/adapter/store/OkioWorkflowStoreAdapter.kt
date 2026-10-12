package io.springkit.workflow.adapter.store

import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StoreEventRequest
import io.springkit.workflow.application.StoreEventResponse
import io.springkit.workflow.application.StoreScope
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreSnapshotResponse
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreTransactionResponse
import io.springkit.workflow.application.StoreTransactionState
import io.springkit.workflow.application.StoreWriteRequest
import io.springkit.workflow.application.StoreWriteResponse
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.common.ExclusiveFileLock
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.WorkflowState
import java.io.IOException
import java.util.UUID
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/*
 * 원자적으로 교체하는 하나의 Okio JSON 문서로 영속화하는 [WorkflowStorePort] 구현입니다.
 */
class OkioWorkflowStoreAdapter(
    private val statePath: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    lockPath: Path? = null,
) : WorkflowStorePort {
  constructor(
      fileSystem: FileSystem,
      statePath: Path,
      lockPath: Path? = null,
  ) : this(
      statePath,
      fileSystem,
      lockPath,
  )

  private val lockPath: Path = lockPath ?: defaultLockPath(statePath)
  private val transactions = mutableMapOf<String, Transaction>()
  private val completed = mutableMapOf<String, StoreTransactionResponse>()
  private val completedKeys = mutableMapOf<String, String?>()

  /*
   * `FakeFileSystem`은 호스트 파일 시스템과 연결되지 않습니다. 따라서 실제 프로세스 간 잠금은 Okio 시스템 파일 시스템에서만 사용하며,
   * `FileChannel` 자체의 격리는 [ExclusiveFileLock]이 담당합니다.
   */
  private val useProcessLock: Boolean = fileSystem === FileSystem.SYSTEM

  @Synchronized
  override fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    completed[request.transactionId]?.let { previous ->
      return if (completedKeys[request.transactionId] == request.idempotencyKey) {
        PortResult.Success(previous)
      } else {
        failure(Codes.IDEMPOTENCY_CONFLICT, "transaction id was already completed")
      }
    }
    transactions[request.transactionId]?.let { open ->
      return if (open.idempotencyKey == request.idempotencyKey) {
        PortResult.Success(
            StoreTransactionResponse(
                request.transactionId,
                StoreTransactionState.OPEN,
                open.base.storeRevision.toString(),
            )
        )
      } else {
        failure(Codes.IDEMPOTENCY_CONFLICT, "transaction id is already open")
      }
    }

    val current = readStateResult() ?: return lastReadFailure
    val revision = current.storeRevision.toString()
    if (request.expectedRevision != null && request.expectedRevision != revision) {
      return failure(
          Codes.STALE_REVISION,
          "expected store revision ${request.expectedRevision}, current revision is $revision",
          retryable = true,
      )
    }

    val lock =
        if (useProcessLock) {
          try {
            ExclusiveFileLock.tryAcquire(lockPath)
          } catch (failure: IOException) {
            return failure(Codes.STORE_IO_FAILURE, "could not open store lock: ${failure.message}")
          }
        } else {
          null
        }
    if (useProcessLock && lock == null) {
      return failure(Codes.LOCK_UNAVAILABLE, "workflow store is locked", retryable = true)
    }

    /*
     * 잠금을 얻은 뒤 revision을 확인해야 합니다. 최초 읽기와 잠금 획득 사이에 다른 프로세스가 커밋할 수 있습니다.
     */
    val lockedState =
        if (useProcessLock) {
          try {
            readState()
          } catch (failure: Throwable) {
            lock?.close()
            return readFailure(failure)
          }
        } else {
          current
        }
    val lockedRevision = lockedState.storeRevision.toString()
    if (request.expectedRevision != null && request.expectedRevision != lockedRevision) {
      lock?.close()
      return failure(
          Codes.STALE_REVISION,
          "expected store revision ${request.expectedRevision}, current revision is $lockedRevision",
          retryable = true,
      )
    }
    transactions[request.transactionId] =
        Transaction(request.transactionId, request.idempotencyKey, lockedState, lock)
    return PortResult.Success(
        StoreTransactionResponse(request.transactionId, StoreTransactionState.OPEN, lockedRevision)
    )
  }

  @Synchronized
  override fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse> {
    val state = readStateResult() ?: return lastReadFailure
    return try {
      PortResult.Success(StoreSnapshotResponse(state.toSnapshot().scoped(request)))
    } catch (failure: IllegalArgumentException) {
      failure(Codes.INVALID_SNAPSHOT, failure.message ?: "invalid workflow state")
    }
  }

  @Synchronized
  override fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse> {
    val transaction = transactions[request.transactionId] ?: return transactionMissing()
    val current = currentLockedState(transaction) ?: return lastReadFailure
    val currentRevision = current.storeRevision.toString()
    if (
        currentRevision != transaction.base.storeRevision.toString() ||
            request.expectedRevision != currentRevision
    ) {
      return failure(
          Codes.STALE_REVISION,
          "expected store revision ${request.expectedRevision}, current revision is $currentRevision",
          retryable = true,
      )
    }
    if (request.snapshot.revision != request.expectedRevision) {
      return failure(
          Codes.STALE_REVISION,
          "snapshot revision ${request.snapshot.revision} does not match expected revision ${request.expectedRevision}",
          retryable = true,
      )
    }
    val next =
        try {
          stateFromSnapshot(request.snapshot).copy(storeRevision = current.storeRevision + 1)
        } catch (failure: IllegalArgumentException) {
          return failure(Codes.INVALID_SNAPSHOT, failure.message ?: "invalid workflow snapshot")
        }
    transaction.pending = next
    return PortResult.Success(StoreWriteResponse(next.storeRevision.toString()))
  }

  @Synchronized
  override fun append(request: StoreEventRequest): PortResult<StoreEventResponse> {
    val transaction = transactions[request.transactionId] ?: return transactionMissing()
    val base = transaction.pending ?: transaction.base
    val next =
        base.copy(
            storeRevision = base.storeRevision + 1,
            eventLog = base.eventLog.append(request.event, request.audit),
        )
    transaction.pending = next
    return PortResult.Success(StoreEventResponse(request.event, next.storeRevision.toString()))
  }

  @Synchronized
  override fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    val transaction = transactions[request.transactionId]
    if (transaction == null) {
      return completed[request.transactionId]?.let { PortResult.Success(it) }
          ?: transactionMissing()
    }
    val current = currentLockedState(transaction) ?: return lastReadFailure
    if (
        current.storeRevision != transaction.base.storeRevision ||
            (request.expectedRevision != null &&
                request.expectedRevision != current.storeRevision.toString())
    ) {
      return failure(
          Codes.STALE_REVISION,
          "store revision changed while transaction was open",
          retryable = true,
      )
    }
    val committed = transaction.pending ?: current
    try {
      if (transaction.pending != null) writeAtomically(committed, request.transactionId)
    } catch (failure: Throwable) {
      return writeFailure(failure)
    }
    val response =
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.COMMITTED,
            committed.storeRevision.toString(),
        )
    transactions.remove(request.transactionId)
    completed[request.transactionId] = response
    completedKeys[request.transactionId] = transaction.idempotencyKey
    transaction.lock?.close()
    return PortResult.Success(response)
  }

  @Synchronized
  override fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse> {
    val transaction = transactions.remove(request.transactionId)
    if (transaction == null) {
      return completed[request.transactionId]?.let { PortResult.Success(it) }
          ?: transactionMissing()
    }
    val response =
        StoreTransactionResponse(
            request.transactionId,
            StoreTransactionState.ROLLED_BACK,
            transaction.base.storeRevision.toString(),
        )
    completed[request.transactionId] = response
    completedKeys[request.transactionId] = transaction.idempotencyKey
    transaction.lock?.close()
    return PortResult.Success(response)
  }

  private fun currentLockedState(transaction: Transaction): WorkflowState? =
      try {
        if (useProcessLock) readState() else transaction.base
      } catch (failure: Throwable) {
        lastReadFailure = readFailure(failure)
        null
      }

  private fun readStateResult(): WorkflowState? =
      try {
        readState()
      } catch (failure: Throwable) {
        lastReadFailure = readFailure(failure)
        null
      }

  private fun readState(): WorkflowState {
    if (!fileSystem.exists(statePath)) return WorkflowState()
    val json = fileSystem.source(statePath).buffer().use { it.readUtf8() }
    return WorkflowStateJsonCodec.decode(json)
  }

  private fun writeAtomically(state: WorkflowState, transactionId: String) {
    val parent = statePath.parent
    if (parent != null) fileSystem.createDirectories(parent)
    val temporary =
        siblingPath(
            statePath,
            ".${statePath.name}.tmp-${safeFilePart(transactionId)}-${UUID.randomUUID()}",
        )
    try {
      fileSystem.sink(temporary).buffer().use {
        it.writeUtf8(WorkflowStateJsonCodec.encodeToString(state))
      }
      fileSystem.atomicMove(temporary, statePath)
    } catch (failure: Throwable) {
      runCatching { fileSystem.delete(temporary) }
      throw failure
    }
  }

  private fun stateFromSnapshot(snapshot: WorkflowStoreSnapshot): WorkflowState {
    require(snapshot.schemaVersion == WorkflowStateJsonCodec.CURRENT_SCHEMA_VERSION) {
      "unsupported store snapshot schema version: ${snapshot.schemaVersion}"
    }
    val revision = snapshot.revision.toLongOrNull()
    require(revision != null && revision >= 0) { "store revision must be a non-negative number" }

    requireUnique(snapshot.tasks.map { it.id }, "task")
    requireUnique(snapshot.subTasks.map { it.id }, "subtask")
    requireUnique(snapshot.pullRequests.map { it.id }, "pull request")
    requireUnique(snapshot.workspaces.map { it.id }, "workspace")
    requireUnique(snapshot.dependencies.map { it.subTaskId }, "dependency subtask")
    requireUnique(snapshot.mergeQueue.map { it.id }, "merge queue")
    requireUnique(snapshot.candidates.map { it.id }, "candidate")
    requireUnique(snapshot.releases.map { it.id }, "release")

    val subTasks = snapshot.subTasks.associateBy { it.id }
    val expectedDependencies =
        subTasks.values
            .mapNotNull { it.requires?.let { required -> Dependency(it.id, required) } }
            .toSet()
    require(snapshot.dependencies.toSet() == expectedDependencies) {
      "snapshot dependencies must match subtask requires"
    }
    val expectedWorkspaces = subTasks.values.mapNotNull { it.workspace }.associateBy { it.id }
    val actualWorkspaces = snapshot.workspaces.associateBy { it.id }
    require(actualWorkspaces == expectedWorkspaces) {
      "snapshot workspaces must match subtask workspaces"
    }

    return WorkflowState(
        schemaVersion = snapshot.schemaVersion,
        storeRevision = revision,
        sequence = snapshot.sequence,
        tasks = snapshot.tasks.associateBy { it.id },
        subTasks = subTasks,
        pullRequests = snapshot.pullRequests.associateBy { it.id },
        checks = snapshot.checks,
        integrations = snapshot.integrations.associateBy { it.subTaskId },
        mergeQueue = snapshot.mergeQueue.associateBy { it.id },
        deploymentCandidates = snapshot.candidates.associateBy { it.id },
        releases = snapshot.releases.associateBy { it.id },
        startRequests = snapshot.startRequests,
        syncConflicts = snapshot.syncConflicts,
        eventLog = snapshot.eventLog,
    )
  }

  private fun WorkflowState.toSnapshot(): WorkflowStoreSnapshot {
    val subTaskValues = subTasks.values.sortedBy { it.id }
    return WorkflowStoreSnapshot(
        revision = storeRevision.toString(),
        schemaVersion = schemaVersion,
        sequence = sequence,
        tasks = tasks.values.sortedBy { it.id },
        subTasks = subTaskValues,
        dependencies =
            subTaskValues.mapNotNull {
              it.requires?.let { required -> Dependency(it.id, required) }
            },
        workspaces = subTaskValues.mapNotNull { it.workspace }.sortedBy { it.id },
        pullRequests = pullRequests.values.sortedBy { it.id },
        checks = checks,
        integrations = integrations.values.sortedBy { it.subTaskId },
        mergeQueue = mergeQueue.values.sortedBy { it.id },
        candidates = deploymentCandidates.values.sortedBy { it.id },
        releases = releases.values.sortedBy { it.id },
        startRequests = startRequests,
        syncConflicts = syncConflicts,
        eventLog = eventLog,
    )
  }

  private fun WorkflowStoreSnapshot.scoped(request: StoreSnapshotRequest): WorkflowStoreSnapshot {
    if (request.scope == StoreScope.ALL) return this
    val taskIds = request.taskId?.let(::setOf) ?: tasks.map { it.id }.toSet()
    val subTaskIds =
        when {
          request.subTaskId != null -> setOf(request.subTaskId)
          request.taskId != null -> subTasks.filter { it.taskId in taskIds }.map { it.id }.toSet()
          else -> subTasks.map { it.id }.toSet()
        }
    return when (request.scope) {
      StoreScope.TASK ->
          copy(
              tasks = tasks.filter { it.id in taskIds },
              subTasks = subTasks.filter { it.id in subTaskIds },
          )
      StoreScope.SUBTASK -> copy(subTasks = subTasks.filter { it.id in subTaskIds })
      StoreScope.WORKSPACE ->
          copy(
              subTasks = subTasks.filter { it.id in subTaskIds },
              workspaces = workspaces.filter { it.subTaskId in subTaskIds },
          )
      StoreScope.REVIEW ->
          copy(
              subTasks = subTasks.filter { it.id in subTaskIds },
              pullRequests = pullRequests.filter { it.subTaskId in subTaskIds },
          )
      StoreScope.INTEGRATION ->
          copy(
              subTasks = subTasks.filter { it.id in subTaskIds },
              integrations = integrations.filter { it.subTaskId in subTaskIds },
              mergeQueue = mergeQueue.filter { it.subTaskId in subTaskIds },
          )
      StoreScope.DEPLOYMENT ->
          copy(
              candidates =
                  candidates.filter { request.candidateId == null || it.id == request.candidateId }
          )
      StoreScope.RELEASE ->
          copy(
              releases = releases.filter { request.releaseId == null || it.id == request.releaseId }
          )
      StoreScope.ALL -> this
    }
  }

  private fun requireUnique(values: List<String>, label: String) {
    require(values.distinct().size == values.size) { "$label ids must be unique" }
  }

  private fun transactionMissing(): PortResult<Nothing> =
      failure(Codes.TRANSACTION_NOT_FOUND, "store transaction is not open")

  private fun <T> failure(
      code: String,
      message: String,
      retryable: Boolean = false,
      target: String? = null,
  ): PortResult<T> = PortResult.Failure(PortError(code, message, retryable, target))

  private fun readFailure(failure: Throwable): PortResult<Nothing> =
      when (failure) {
        is StateDecodeException ->
            PortResult.Failure(
                PortError(Codes.STORE_CORRUPT, failure.message ?: "invalid store JSON")
            )
        is IllegalArgumentException ->
            PortResult.Failure(
                PortError(Codes.STORE_CORRUPT, failure.message ?: "invalid workflow state")
            )
        else ->
            PortResult.Failure(
                PortError(
                    Codes.STORE_IO_FAILURE,
                    failure.message ?: "could not read workflow store",
                    retryable = true,
                )
            )
      }

  private fun writeFailure(failure: Throwable): PortResult<Nothing> =
      PortResult.Failure(
          PortError(
              Codes.STORE_IO_FAILURE,
              failure.message ?: "could not commit workflow store",
              retryable = true,
          )
      )

  private fun safeFilePart(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

  private var lastReadFailure: PortResult<Nothing> =
      PortResult.Failure(
          PortError(Codes.STORE_IO_FAILURE, "could not read workflow store", retryable = true)
      )

  private data class Transaction(
      val id: String,
      val idempotencyKey: String?,
      val base: WorkflowState,
      val lock: ExclusiveFileLock?,
      var pending: WorkflowState? = null,
  )

  private object Codes {
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val INVALID_SNAPSHOT = "INVALID_SNAPSHOT"
    const val LOCK_UNAVAILABLE = "LOCK_UNAVAILABLE"
    const val STALE_REVISION = "STALE_REVISION"
    const val STORE_CORRUPT = "STORE_CORRUPT"
    const val STORE_IO_FAILURE = "STORE_IO_FAILURE"
    const val TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND"
  }

  companion object {
    private fun defaultLockPath(statePath: Path): Path =
        siblingPath(statePath, ".${statePath.name}.lock")

    private fun siblingPath(path: Path, name: String): Path =
        path.parent?.resolve(name) ?: name.toPath()
  }
}
