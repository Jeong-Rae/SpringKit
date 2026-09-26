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
import io.springkit.workflow.adapter.provider.ProviderDeploymentAdapter
import io.springkit.workflow.adapter.provider.ProviderFeatureFlagAdapter
import io.springkit.workflow.adapter.provider.ProviderReleaseAdapter
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
import io.springkit.workflow.application.DeploymentLifecycleRequest
import io.springkit.workflow.application.DeploymentLifecycleResponse
import io.springkit.workflow.application.DeploymentLifecycleUseCase
import io.springkit.workflow.application.DeploymentPort
import io.springkit.workflow.application.FeatureFlagPort
import io.springkit.workflow.application.GetCandidateRequest
import io.springkit.workflow.application.GetCandidateResponse
import io.springkit.workflow.application.GetReleaseRequest
import io.springkit.workflow.application.GetReleaseResponse
import io.springkit.workflow.application.IdKind
import io.springkit.workflow.application.IssueIdRequest
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PostMergeCleanupResponse
import io.springkit.workflow.application.PostMergeCleanupUseCase
import io.springkit.workflow.application.ReleaseLifecycleUseCases
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
import io.springkit.workflow.application.WorkflowStorePort
import io.springkit.workflow.application.WorkflowStoreSnapshot
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.IntegrationState
import io.springkit.workflow.domain.MergeRecorded
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/** 런타임 구성을 외부 환경과 분리해 테스트할 수 있는 구성 경계입니다. */
fun interface RuntimeFactory {
  fun create(): WorkflowCommandGateway
}

/** 기본 실행 환경에서 실제 로컬·GitHub 어댑터를 조합하는 Workflow 런타임입니다. */
object DefaultRuntimeFactory : RuntimeFactory {
  override fun create(): WorkflowCommandGateway = createDefaultRuntime()
}

/** 실행 환경 설정이 Workflow 초기화를 막을 때 사용하는 명시적인 오류입니다. */
internal class WorkflowConfigurationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

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
  validateActorKind(environment)
  val workingDirectory = currentDirectory.toAbsolutePath().normalize()
  val repositoryRoot = resolveRepositoryRoot(workingDirectory, environment, commandRunner)
  val configuredStatePath =
      environment["WORKFLOW_STATE_FILE"]?.let {
        configurationPath("WORKFLOW_STATE_FILE", it)
      }
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
  val workspacePort = LocalWorkspaceAdapter(store, { workingDirectory }, repositoryRoot)
  val workspacePath = workspacePathResolver(store, repositoryRoot)
  val git = LocalGitAdapter(repositoryRoot, workspacePath, commandRunner, repositoryRoot)
  val task =
      createTaskPort(
          repositoryRoot = repositoryRoot,
          environment = environment,
          commandRunner = commandRunner,
          snapshotProvider = { snapshot(store) },
      )
  val providerCommands = providerCommands(environment)
  val deploymentPort: DeploymentPort =
      providerCommands?.deployment?.let {
        ProviderDeploymentAdapter(
            commandPrefix = it,
            workingDirectory = repositoryRoot,
            commandRunner = commandRunner,
        )
      } ?: UnavailableDeploymentPort
  val releasePort: ReleasePort =
      providerCommands?.release?.let {
        ProviderReleaseAdapter(
            commandPrefix = it,
            workingDirectory = repositoryRoot,
            commandRunner = commandRunner,
        )
      } ?: UnavailableReleasePort
  val featureFlagPort: FeatureFlagPort? =
      providerCommands?.featureFlag?.let {
        ProviderFeatureFlagAdapter(
            commandPrefix = it,
            workingDirectory = repositoryRoot,
            commandRunner = commandRunner,
        )
      }
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
  val review =
      ReviewUseCases(
          reviewPort = reviewAdapter,
          idPort = idPort,
          clockPort = clock,
          compensationPort = RuntimeCompensationPort,
          storePort = store,
      )
  val reviewLifecycle =
      ReviewLifecycleUseCases(
          workspacePort = workspacePort,
          gitPort = git,
          gitPublishPort = git,
          reviewPort = reviewAdapter,
          ciPort = ci,
          storePort = store,
          clockPort = clock,
          featureFlagPort = featureFlagPort,
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
  val deploymentLifecycle =
      DeploymentLifecycleUseCase(
          storePort = store,
          deploymentPort = deploymentPort,
          idPort = idPort,
          clockPort = clock,
          compensationPort = RuntimeCompensationPort,
          featureFlagPort = featureFlagPort,
      )
  val releaseLifecycle =
      ReleaseLifecycleUseCases(
          storePort = store,
          releasePort = releasePort,
          idPort = idPort,
          clockPort = clock,
          compensationPort = RuntimeCompensationPort,
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
          deploymentPort = deploymentPort.takeIf { providerCommands != null },
          releasePort = releasePort.takeIf { providerCommands != null },
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
          deploymentPort = deploymentPort,
          releasePort = releasePort,
          identityPort = identity,
          idPort = idPort,
          clockPort = clock,
          compensationPort = RuntimeCompensationPort,
          featureFlagPort = featureFlagPort,
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
      createWorkflowEventUseCases(
          eventPort = eventPort,
          providerConfigured = providerCommands != null,
          mergeQueueLifecycle = mergeQueueLifecycle,
          postMergeCleanup = postMergeCleanup,
          deploymentLifecycle = deploymentLifecycle,
          releaseLifecycle = releaseLifecycle,
          store = store,
      )
  return WorkflowApplicationRuntime(
      commandGateway = commandGateway,
      eventUseCases = eventUseCases,
      postMergeCleanup = postMergeCleanup,
      deploymentLifecycle = deploymentLifecycle,
      releaseLifecycle = releaseLifecycle,
  )
}

/** Production 후보 뒤에 기록된 merge 중 가장 최신 revision으로 다음 후보를 만듭니다. */
internal fun createNextCandidateAfterProduction(
    store: WorkflowStorePort,
    deploymentLifecycle: DeploymentLifecycleUseCase,
    production: DeploymentCandidate,
    requestId: String,
): WorkflowResult<DeploymentLifecycleResponse?> {
  val stored =
      when (val result = store.snapshot(StoreSnapshotRequest())) {
        is PortResult.Failure ->
            return WorkflowResult.Failure(
                FailureData(
                    code = FailureCode.STORE_FAILURE,
                    message = result.error.message,
                    blockedBy =
                        listOf(
                            BlockedBy(
                                code = result.error.code,
                                message = result.error.message,
                                target = "store",
                            )
                        ),
                )
            )
        is PortResult.Success -> result.value.snapshot
      }
  val latestRevision =
      latestMergedRevisionAfter(stored, production.mainRevision)
          ?: return WorkflowResult.Success(null)
  val created =
      deploymentLifecycle.create(
          DeploymentLifecycleRequest(
              mainRevision = latestRevision,
              requestId = "next-candidate-${production.id}-$requestId",
          )
      )
  return when (created) {
    is WorkflowResult.Failure -> created
    is WorkflowResult.Success -> WorkflowResult.Success(created.data, created.next)
  }
}

/** Store가 보존한 merge 순서에서 현재 Production 이후의 최신 main revision을 찾습니다. */
private fun latestMergedRevisionAfter(
    snapshot: WorkflowStoreSnapshot,
    productionRevision: String,
): String? {
  val includedSubTasks =
      snapshot.candidates
          .firstOrNull { it.mainRevision == productionRevision }
          ?.includedSubTasks
          ?.toSet()
          .orEmpty()
  val mergeEvents = snapshot.eventLog.events.filterIsInstance<MergeRecorded>()
  val productionEventIndex = mergeEvents.indexOfLast { it.mainRevision == productionRevision }
  val eventRevisions =
      if (productionEventIndex >= 0) {
        mergeEvents
            .drop(productionEventIndex + 1)
            .filter { it.targetId !in includedSubTasks }
            .map { it.mainRevision }
      } else {
        emptyList()
      }
  val integrationRevisions =
      snapshot.integrations
          .asSequence()
          .filter { it.state == IntegrationState.MERGED }
          .filter { it.subTaskId !in includedSubTasks }
          .mapNotNull { it.mainRevision }
          .toList()
  return (eventRevisions + integrationRevisions)
      .filter { it.isNotBlank() && it != productionRevision }
      .lastOrNull()
}

private data class ProviderCommandPrefixes(
    val deployment: List<String>,
    val release: List<String>,
    val featureFlag: List<String>,
)

/** 세 provider 명령 설정을 모두 구성했는지 확인하고 JSON 배열을 해석합니다. */
private fun providerCommands(environment: Map<String, String>): ProviderCommandPrefixes? {
  val values =
      mapOf(
              "WORKFLOW_DEPLOYMENT_COMMAND" to environment["WORKFLOW_DEPLOYMENT_COMMAND"],
              "WORKFLOW_RELEASE_COMMAND" to environment["WORKFLOW_RELEASE_COMMAND"],
              "WORKFLOW_FEATURE_FLAG_COMMAND" to environment["WORKFLOW_FEATURE_FLAG_COMMAND"],
          )
          .mapValues { (_, value) -> value?.trim()?.takeIf(String::isNotBlank) }
  if (values.values.all { it == null }) return null
  val missing = values.filterValues { it == null }.keys
  if (missing.isNotEmpty()) {
    throw WorkflowConfigurationException(
        "provider CLI 설정은 세 환경 변수를 모두 지정해야 합니다. 누락: ${missing.joinToString(", ")}"
    )
  }
  return ProviderCommandPrefixes(
      deployment =
          decodeProviderCommand(
              "WORKFLOW_DEPLOYMENT_COMMAND",
              values.getValue("WORKFLOW_DEPLOYMENT_COMMAND")!!,
          ),
      release =
          decodeProviderCommand(
              "WORKFLOW_RELEASE_COMMAND",
              values.getValue("WORKFLOW_RELEASE_COMMAND")!!,
          ),
      featureFlag =
          decodeProviderCommand(
              "WORKFLOW_FEATURE_FLAG_COMMAND",
              values.getValue("WORKFLOW_FEATURE_FLAG_COMMAND")!!,
          ),
  )
}

/** provider CLI 환경 변수를 문자열 배열로 해석하고 실행 가능한 명령인지 확인합니다. */
private fun decodeProviderCommand(name: String, value: String): List<String> {
  val command =
      try {
        providerCommandJson.decodeFromString<List<String>>(value)
      } catch (failure: SerializationException) {
        throw WorkflowConfigurationException(
            "$name 값은 provider CLI 토큰의 JSON 문자열 배열이어야 합니다.",
            failure,
        )
      }
  if (command.isEmpty() || command.any(String::isBlank)) {
    throw WorkflowConfigurationException("$name 값은 비어 있지 않은 provider CLI 토큰 배열이어야 합니다.")
  }
  return command
}

private val providerCommandJson = Json { explicitNulls = false }

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
          throw WorkflowConfigurationException(
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
    val configured = configurationPath("WORKFLOW_REPO_ROOT", it)
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

/** 문서에 정의된 실행 주체 종류만 런타임 구성으로 허용합니다. */
private fun validateActorKind(environment: Map<String, String>) {
  val configured =
      environment["WORKFLOW_ACTOR_KIND"]?.trim()?.takeIf(String::isNotBlank)?.uppercase()
  if (configured != null && configured !in setOf("AGENT", "WORKFLOW")) {
    throw WorkflowConfigurationException("WORKFLOW_ACTOR_KIND는 AGENT 또는 WORKFLOW여야 합니다.")
  }
}

/** 환경 변수의 경로를 해석하고 잘못된 경로를 구성 오류로 변환합니다. */
private fun configurationPath(name: String, value: String): Path =
    try {
      Path.of(value)
    } catch (failure: InvalidPathException) {
      throw WorkflowConfigurationException("$name 값이 올바른 경로가 아닙니다.", failure)
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
internal fun snapshot(store: WorkflowStorePort): WorkflowStoreSnapshot =
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

/** 배포 실패에 독립적인 cleanup 차단 원인과 재시도 행동을 함께 보존합니다. */
internal fun WorkflowResult.Failure.withCleanupRetry(
    cleanup: WorkflowResult<PostMergeCleanupResponse>,
    mergedSubTaskId: String,
): WorkflowResult.Failure {
  val cleanupBlocks =
      when (cleanup) {
        is WorkflowResult.Success -> {
          if (
              cleanup.data.state ==
                  io.springkit.workflow.application.PostMergeCleanupState.COMPLETED
          ) {
            return this
          }
          cleanup.data.blocks.map {
            BlockedBy(
                code = it.code,
                message = it.message,
                target = it.target,
            )
          }
        }
        is WorkflowResult.Failure ->
            cleanup.data.blockedBy.ifEmpty {
              listOf(
                  BlockedBy(
                      code = cleanup.data.code.name,
                      message = cleanup.data.message,
                      target = mergedSubTaskId,
                  )
              )
            }
      }
  val cleanupNext =
      when (cleanup) {
        is WorkflowResult.Success -> cleanup.next
        is WorkflowResult.Failure -> cleanup.data.next
      } +
          io.springkit.workflow.domain.NextAction(
              io.springkit.workflow.domain.ActorKind.WORKFLOW,
              "retry_cleanup",
          )
  return WorkflowResult.Failure(
      data.copy(
          blockedBy = (data.blockedBy + cleanupBlocks).distinct(),
          next =
              (data.next + cleanupNext).distinctBy {
                Triple(it.actor, it.action, it.command)
              },
      )
  )
}

/** 외부 어댑터가 조회한 Merge Queue 항목을 잠금 가능한 Store에 반영합니다. */
private fun persistMergeQueueEntry(
    store: WorkflowStorePort,
    entry: io.springkit.workflow.domain.MergeQueueEntry,
) {
  val current = store.snapshot(StoreSnapshotRequest())
  val snapshot =
      when (current) {
        is PortResult.Failure -> return
        is PortResult.Success -> current.value.snapshot
      }
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
