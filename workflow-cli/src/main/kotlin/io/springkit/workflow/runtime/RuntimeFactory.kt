package io.springkit.workflow.runtime

import io.springkit.workflow.adapter.cli.WorkflowCommandGateway
import io.springkit.workflow.adapter.git.LocalGitAdapter
import io.springkit.workflow.adapter.github.GithubCiAdapter
import io.springkit.workflow.adapter.github.GithubIssueTaskAdapter
import io.springkit.workflow.adapter.github.GithubMergeQueueAdapter
import io.springkit.workflow.adapter.local.EnvironmentIdentityAdapter
import io.springkit.workflow.adapter.local.LocalWorkspaceAdapter
import io.springkit.workflow.adapter.local.SnapshotTaskAdapter
import io.springkit.workflow.adapter.local.SystemClockAdapter
import io.springkit.workflow.adapter.store.OkioWorkflowStoreAdapter
import io.springkit.workflow.adapter.store.StoreIdAdapter
import io.springkit.workflow.adapter.validation.LocalValidationAdapter
import io.springkit.workflow.application.CompensateRequest
import io.springkit.workflow.application.CompensateResponse
import io.springkit.workflow.application.CompensationPort
import io.springkit.workflow.application.ContentPort
import io.springkit.workflow.application.CreateCandidateRequest
import io.springkit.workflow.application.CreateCandidateResponse
import io.springkit.workflow.application.CreateReleaseRequest
import io.springkit.workflow.application.CreateReleaseResponse
import io.springkit.workflow.application.DeploymentPort
import io.springkit.workflow.application.ExternalEventKind
import io.springkit.workflow.application.GetCandidateRequest
import io.springkit.workflow.application.GetCandidateResponse
import io.springkit.workflow.application.GetReleaseRequest
import io.springkit.workflow.application.GetReleaseResponse
import io.springkit.workflow.application.IdKind
import io.springkit.workflow.application.IssueIdRequest
import io.springkit.workflow.application.MergeQueueLifecycleRequest
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PostMergeCleanupRequest
import io.springkit.workflow.application.PostMergeCleanupUseCase
import io.springkit.workflow.application.ReleasePort
import io.springkit.workflow.application.ReviewLifecycleUseCases
import io.springkit.workflow.application.ReviewUseCases
import io.springkit.workflow.application.StackSyncUseCases
import io.springkit.workflow.application.StartCanaryRequest
import io.springkit.workflow.application.StartCanaryResponse
import io.springkit.workflow.application.StartCheckUseCases
import io.springkit.workflow.application.StartReleaseRequest
import io.springkit.workflow.application.StartReleaseResponse
import io.springkit.workflow.application.StatusUseCase
import io.springkit.workflow.application.StoreSnapshotRequest
import io.springkit.workflow.application.StoreTransactionRequest
import io.springkit.workflow.application.StoreTransactionState
import io.springkit.workflow.application.StoreWriteRequest
import io.springkit.workflow.application.TaskPort
import io.springkit.workflow.application.ValidateCandidateRequest
import io.springkit.workflow.application.ValidateCandidateResponse
import io.springkit.workflow.application.ValidateReleaseRequest
import io.springkit.workflow.application.ValidateReleaseResponse
import io.springkit.workflow.application.WorkflowEventHandler
import io.springkit.workflow.application.WorkflowEventUseCases
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.common.CommandRunner
import java.nio.file.Path
import okio.Path.Companion.toPath

/** 런타임 구성을 외부 환경과 분리해 테스트할 수 있는 구성 경계입니다. */
fun interface RuntimeFactory {
  fun create(): WorkflowCommandGateway
}

/** 기본 실행 환경에서 실제 로컬·GitHub 어댑터를 조합하는 Workflow 런타임입니다. */
object DefaultRuntimeFactory : RuntimeFactory {
  override fun create(): WorkflowCommandGateway = createDefaultRuntime()
}

/** 로컬 실행 환경에서 Workflow Application과 Outbound Adapter를 조합합니다. */
fun createDefaultRuntime(
    currentDirectory: Path = Path.of(System.getProperty("user.dir")),
    environment: Map<String, String> = System.getenv(),
    commandRunner: io.springkit.workflow.common.CommandRunner =
        io.springkit.workflow.common.LocalCommandRunner(),
): WorkflowCommandGateway =
    createDefaultApplicationRuntime(
            currentDirectory = currentDirectory,
            environment = environment,
            commandRunner = commandRunner,
        )
        .commandGateway

/** 기본 실행 환경의 Application과 Adapter를 모두 조합한 런타임을 만듭니다. */
fun createDefaultApplicationRuntime(
    currentDirectory: Path = Path.of(System.getProperty("user.dir")),
    environment: Map<String, String> = System.getenv(),
    commandRunner: io.springkit.workflow.common.CommandRunner =
        io.springkit.workflow.common.LocalCommandRunner(),
    eventPort: io.springkit.workflow.application.WorkflowEventPort = UnconfiguredWorkflowEventPort,
): WorkflowApplicationRuntime {
  val workingDirectory = currentDirectory.toAbsolutePath().normalize()
  val repositoryRoot = resolveRepositoryRoot(workingDirectory, environment, commandRunner)
  val configuredStatePath = environment["WORKFLOW_STATE_FILE"]?.let(Path::of)
  val statePath =
      (configuredStatePath?.let { if (it.isAbsolute) it else workingDirectory.resolve(it) }
              ?: repositoryRoot.resolve(".workflow").resolve("state.json"))
          .toAbsolutePath()
          .normalize()
  val store = OkioWorkflowStoreAdapter(statePath.toString().toPath())
  val clock = SystemClockAdapter()
  val identity =
      EnvironmentIdentityAdapter(
          commandRunner = commandRunner,
          workingDirectory = workingDirectory,
          environment = environment,
          humanActorIds = environment.humanActorIds(),
      )
  val idPort = StoreIdAdapter(store)
  val workspacePort = LocalWorkspaceAdapter(store) { workingDirectory }
  val workspacePath = workspacePathResolver(store, repositoryRoot)
  val git = LocalGitAdapter(repositoryRoot, workspacePath, commandRunner)
  val task =
      createTaskPort(
          repositoryRoot = repositoryRoot,
          environment = environment,
          commandRunner = commandRunner,
          snapshotProvider = { snapshot(store) },
      )
  val validation = LocalValidationAdapter(workspacePath, commandRunner = commandRunner)
  val reviewAdapter =
      io.springkit.workflow.adapter.github.GithubReviewAdapter(
          repositoryRoot = repositoryRoot,
          commandRunner = commandRunner,
          currentPullRequest = { pullRequestId ->
            snapshot(store).pullRequests.firstOrNull { it.id == pullRequestId }
          },
      )
  val ci = GithubCiAdapter(repositoryRoot, commandRunner)
  val mergeQueue =
      GithubMergeQueueAdapter(
          repositoryRoot = repositoryRoot,
          commandRunner = commandRunner,
          entryLookup = { entryId ->
            snapshot(store).mergeQueue.firstOrNull {
              it.id == entryId || it.pullRequestId == entryId
            }
          },
          entryPersister = { entry -> persistMergeQueueEntry(store, entry) },
      )
  val content = GitContentAdapter(git)
  val context = StoreWorkflowRuntimeContext(store, workspacePort, git, identity)
  val startCheck =
      StartCheckUseCases(
          taskPort = task,
          gitPort = git,
          workspacePort = workspacePort,
          storePort = store,
          validationPort = validation,
          idPort = idPort,
          clockPort = clock,
          projectPrefix = environment["WORKFLOW_PROJECT_PREFIX"] ?: "sk",
      )
  val review = ReviewUseCases(reviewAdapter, idPort, clock)
  val reviewLifecycle =
      ReviewLifecycleUseCases(
          workspacePort = workspacePort,
          gitPort = git,
          gitPublishPort = git,
          reviewPort = reviewAdapter,
          ciPort = ci,
          storePort = store,
          clockPort = clock,
      )
  val stackSync = StackSyncUseCases(git, reviewAdapter, store, idPort, clockPort = clock)
  val mergeQueueLifecycle =
      io.springkit.workflow.application.MergeQueueLifecycleUseCase(
          storePort = store,
          mergeQueuePort = mergeQueue,
          taskPort = task,
          idPort = idPort,
          clockPort = clock,
      )
  val status =
      StatusUseCase(
          storePort = store,
          taskPort = task,
          workspacePort = workspacePort,
          contentPort = content,
          gitPort = git,
          reviewPort = reviewAdapter,
          validationPort = validation,
          ciPort = ci,
          mergeQueuePort = mergeQueue,
      )
  val reviewGate =
      io.springkit.workflow.application.ReviewGateUseCases(
          identityPort = identity,
          reviewPort = reviewAdapter,
          storePort = store,
          mergeQueuePort = mergeQueue,
          idPort = idPort,
          clockPort = clock,
      )
  val deliveryGate =
      io.springkit.workflow.application.DeliveryGateUseCases(
          storePort = store,
          deploymentPort = UnavailableDeploymentPort,
          releasePort = UnavailableReleasePort,
          identityPort = identity,
          idPort = idPort,
          clockPort = clock,
          compensationPort = RuntimeCompensationPort,
      )
  val commandGateway =
      WorkflowCommandGateway(
          startCheck = startCheck,
          review = review,
          reviewLifecycle = reviewLifecycle,
          stackSync = stackSync,
          status = status,
          reviewGate = reviewGate,
          deliveryGate = deliveryGate,
          context = context,
          commentId = { requestId ->
            when (val result = idPort.issue(IssueIdRequest(IdKind.COMMENT, requestId))) {
              is PortResult.Success -> {
                val id = result.value.ids.singleOrNull()?.value
                if (id == null) {
                  PortResult.Failure(PortError("ID_ISSUE_INVALID", "Comment ID 발급 결과가 하나가 아닙니다."))
                } else {
                  PortResult.Success(id)
                }
              }
              is PortResult.Failure -> result
            }
          },
      )
  val postMergeCleanup =
      PostMergeCleanupUseCase(
          git,
          reviewAdapter,
          store,
          idPort,
          clock,
      )
  val eventUseCases =
      WorkflowEventUseCases(
          eventPort = eventPort,
          handlers =
              mapOf(
                  ExternalEventKind.MERGE_QUEUE_CHANGED to
                      WorkflowEventHandler { event ->
                        val mergeRequest =
                            MergeQueueLifecycleRequest(
                                subTaskId = event.targetId,
                                mergeQueueEntryId =
                                    event.attributes.optionalAttribute("merge_queue_entry_id"),
                                changeRevisionId =
                                    event.attributes.optionalAttribute("change_revision_id"),
                                requestId = event.id,
                            )
                        when (val merged = mergeQueueLifecycle.execute(mergeRequest)) {
                          is io.springkit.workflow.domain.WorkflowResult.Failure -> merged
                          is io.springkit.workflow.domain.WorkflowResult.Success ->
                              when (
                                  val cleanup =
                                      postMergeCleanup.execute(
                                          PostMergeCleanupRequest(
                                              mergedSubTaskId = merged.data.subTask.id
                                          )
                                      )
                              ) {
                                is io.springkit.workflow.domain.WorkflowResult.Success ->
                                    io.springkit.workflow.domain.WorkflowResult.Success(
                                        MergeQueueEventResponse(
                                            merge = merged.data,
                                            cleanup = cleanup.data,
                                        ),
                                        next = cleanup.next,
                                    )
                                is io.springkit.workflow.domain.WorkflowResult.Failure ->
                                    io.springkit.workflow.domain.WorkflowResult.Success(
                                        MergeQueueEventResponse(
                                            merge = merged.data,
                                            cleanup =
                                                cleanup.data.toBlockedCleanup(
                                                    merged.data.subTask.id
                                                ),
                                        ),
                                        next =
                                            listOf(
                                                io.springkit.workflow.domain.NextAction(
                                                    io.springkit.workflow.domain.ActorKind.WORKFLOW,
                                                    "retry_cleanup",
                                                )
                                            ),
                                    )
                              }
                        }
                      },
                  ExternalEventKind.MAIN_MERGED to
                      WorkflowEventHandler { event ->
                        postMergeCleanup.execute(
                            PostMergeCleanupRequest(mergedSubTaskId = event.targetId)
                        )
                      },
              ),
      )
  return WorkflowApplicationRuntime(
      commandGateway = commandGateway,
      eventUseCases = eventUseCases,
      postMergeCleanup = postMergeCleanup,
  )
}

/** 환경 설정에 따라 외부 Task adapter를 명시적으로 선택합니다. */
internal fun createTaskPort(
    repositoryRoot: Path,
    environment: Map<String, String>,
    commandRunner: CommandRunner,
    snapshotProvider: () -> WorkflowStoreSnapshot,
): TaskPort =
    when (environment["WORKFLOW_TASK_PROVIDER"]?.trim()?.lowercase().orEmpty()) {
      "",
      "snapshot" -> SnapshotTaskAdapter(snapshotProvider)
      "github-issue" -> GithubIssueTaskAdapter(repositoryRoot, commandRunner)
      else ->
          throw IllegalArgumentException(
              "지원하지 않는 WORKFLOW_TASK_PROVIDER입니다. " + "지원 값: snapshot, github-issue",
          )
    }

/** 환경 변수 또는 설치된 git CLI를 사용해 저장소 루트를 찾습니다. */
private fun resolveRepositoryRoot(
    currentDirectory: Path,
    environment: Map<String, String>,
    commandRunner: io.springkit.workflow.common.CommandRunner,
): Path {
  environment["WORKFLOW_REPO_ROOT"]?.let {
    val configured = Path.of(it)
    return (if (configured.isAbsolute) configured else currentDirectory.resolve(configured))
        .toAbsolutePath()
        .normalize()
  }
  return runCatching {
        commandRunner
            .run(listOf("git", "rev-parse", "--show-toplevel"), currentDirectory)
            .takeIf { it.exitCode == 0 && it.stdout.trim().isNotBlank() }
            ?.stdout
            ?.trim()
            ?.let(Path::of)
      }
      .getOrNull()
      ?.toAbsolutePath()
      ?.normalize() ?: currentDirectory
}

/** 저장된 Workspace 경로를 Local Git 어댑터가 사용할 절대 경로로 변환합니다. */
private fun workspacePathResolver(
    store: WorkflowStorePort,
    repositoryRoot: Path,
): (String) -> Path = { workspaceId ->
  when (val result = store.snapshot(StoreSnapshotRequest())) {
    is PortResult.Success -> {
      val workspace = result.value.snapshot.workspaces.first { it.id == workspaceId }
      val path = Path.of(workspace.path.value)
      if (path.isAbsolute) path.normalize() else repositoryRoot.resolve(path).normalize()
    }
    is PortResult.Failure -> error(result.error.message)
  }
}

/** 현재 상태 저장소에서 최신 전체 snapshot을 읽습니다. */
private fun snapshot(store: WorkflowStorePort): WorkflowStoreSnapshot =
    when (val result = store.snapshot(StoreSnapshotRequest())) {
      is PortResult.Success -> result.value.snapshot
      is PortResult.Failure -> error(result.error.message)
    }

/** 환경에서 사람이 수행할 수 있는 주체 목록을 읽습니다. */
private fun Map<String, String>.humanActorIds(): Set<String> =
    (this["WORKFLOW_HUMAN_ACTORS"] ?: this["WORKFLOW_HUMAN_ACTOR_IDS"])
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.toSet() ?: emptySet()

/** 이벤트 속성에서 공백 값과 누락 값을 동일한 선택적 값으로 변환합니다. */
private fun Map<String, String>.optionalAttribute(name: String): String? =
    this[name]?.trim()?.takeIf(String::isNotBlank)

/** 이미 완료된 통합을 되돌리지 않고 cleanup 실패를 재시도 가능한 차단 결과로 변환합니다. */
private fun io.springkit.workflow.domain.FailureData.toBlockedCleanup(
    mergedSubTaskId: String
): io.springkit.workflow.application.PostMergeCleanupResponse =
    io.springkit.workflow.application.PostMergeCleanupResponse(
        mergedSubTaskId = mergedSubTaskId,
        state = io.springkit.workflow.application.PostMergeCleanupState.BLOCKED,
        blocks =
            if (blockedBy.isEmpty()) {
              listOf(
                  io.springkit.workflow.application.PostMergeCleanupBlock(
                      phase = "cleanup",
                      target = mergedSubTaskId,
                      code = code.name,
                      message = message,
                  )
              )
            } else {
              blockedBy.map {
                io.springkit.workflow.application.PostMergeCleanupBlock(
                    phase = "cleanup",
                    target = it.target ?: mergedSubTaskId,
                    code = it.code,
                    message = it.message,
                )
              }
            },
    )

/** 외부 어댑터가 조회한 Merge Queue 항목을 잠금 가능한 Store에 반영합니다. */
private fun persistMergeQueueEntry(
    store: WorkflowStorePort,
    entry: io.springkit.workflow.domain.MergeQueueEntry,
) {
  val current = store.snapshot(StoreSnapshotRequest())
  if (current is PortResult.Failure) return
  val snapshot = (current as PortResult.Success).value.snapshot
  val transaction =
      StoreTransactionRequest(
          transactionId =
              "merge-queue-persist-${entry.id}-${entry.changeRevisionId}-${entry.state.name.lowercase()}",
          expectedRevision = snapshot.revision,
          idempotencyKey = "merge-queue:${entry.id}:${entry.state}",
      )
  val opened = store.begin(transaction)
  if (opened !is PortResult.Success || opened.value.state != StoreTransactionState.OPEN) return
  val updated =
      snapshot.copy(mergeQueue = snapshot.mergeQueue.filterNot { it.id == entry.id } + entry)
  if (
      store.write(StoreWriteRequest(transaction.transactionId, snapshot.revision, updated))
          is PortResult.Failure
  ) {
    store.rollback(transaction)
    return
  }
  store.commit(transaction)
}

/** Git 상태를 Content Port 결과로 변환합니다. */
private class GitContentAdapter(private val gitPort: io.springkit.workflow.application.GitPort) :
    ContentPort {
  override fun inspect(
      request: io.springkit.workflow.application.InspectContentRequest
  ): PortResult<io.springkit.workflow.application.InspectContentResponse> =
      when (
          val result =
              gitPort.inspect(
                  io.springkit.workflow.application.GitInspectRequest(request.workspaceId)
              )
      ) {
        is PortResult.Failure -> result
        is PortResult.Success ->
            PortResult.Success(
                io.springkit.workflow.application.InspectContentResponse(
                    io.springkit.workflow.application.ContentSnapshot(
                        revision = result.value.status.revision,
                        fingerprint = result.value.status.fingerprint,
                        dirty = result.value.status.dirty,
                        files = result.value.status.conflicts,
                    )
                )
            )
      }
}

/** 외부 배포 공급자가 아직 연결되지 않았음을 명시적으로 반환합니다. */
private object UnavailableDeploymentPort : DeploymentPort {
  override fun createCandidate(
      request: CreateCandidateRequest
  ): PortResult<CreateCandidateResponse> = unavailable(request.mainRevision)

  override fun getCandidate(request: GetCandidateRequest): PortResult<GetCandidateResponse> =
      unavailable(request.candidateId)

  override fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse> =
      unavailable(request.candidateId)

  override fun startCanary(request: StartCanaryRequest): PortResult<StartCanaryResponse> =
      unavailable(request.candidateId)

  override fun promoteProduction(
      request: io.springkit.workflow.application.PromoteProductionRequest
  ): PortResult<io.springkit.workflow.application.PromoteProductionResponse> =
      unavailable(request.candidateId)
}

/** 외부 공개 공급자가 아직 연결되지 않았음을 명시적으로 반환합니다. */
private object UnavailableReleasePort : ReleasePort {
  override fun get(request: GetReleaseRequest): PortResult<GetReleaseResponse> =
      unavailable(request.releaseId ?: request.candidateId ?: "release")

  override fun create(request: CreateReleaseRequest): PortResult<CreateReleaseResponse> =
      unavailable(request.releaseId)

  override fun validateInternal(
      request: ValidateReleaseRequest
  ): PortResult<ValidateReleaseResponse> = unavailable(request.releaseId)

  override fun start(request: StartReleaseRequest): PortResult<StartReleaseResponse> =
      unavailable(request.releaseId)

  override fun continueRollout(
      request: io.springkit.workflow.application.ContinueReleaseRequest
  ): PortResult<io.springkit.workflow.application.ContinueReleaseResponse> =
      unavailable(request.releaseId)
}

/** 공급자 미구성 오류를 공통 Port 결과로 만듭니다. */
private fun <T> unavailable(target: Any): PortResult<T> =
    PortResult.Failure(
        PortError(
            code = "PROVIDER_NOT_CONFIGURED",
            message = "이 명령의 외부 공급자가 아직 구성되지 않았습니다.",
            target = target.toString(),
        )
    )

/** 로컬 실행에서 외부 보상 동작을 원자적인 성공 응답으로 기록합니다. */
private object RuntimeCompensationPort : CompensationPort {
  override fun compensate(request: CompensateRequest): PortResult<CompensateResponse> =
      PortResult.Success(CompensateResponse(request.change))
}
