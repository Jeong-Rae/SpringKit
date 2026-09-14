package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.AiReviewStatus
import io.springkit.workflow.domain.Approval
import io.springkit.workflow.domain.AuditEntry
import io.springkit.workflow.domain.BranchName
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.ChangeRevision
import io.springkit.workflow.domain.ChangeRevisionId
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.CiRun
import io.springkit.workflow.domain.CiStatus
import io.springkit.workflow.domain.Dependency
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Diff
import io.springkit.workflow.domain.DomainEvent
import io.springkit.workflow.domain.EventLog
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.FeatureFlagId
import io.springkit.workflow.domain.IdSequence
import io.springkit.workflow.domain.Integration
import io.springkit.workflow.domain.MainRevision
import io.springkit.workflow.domain.MergeQueueEntry
import io.springkit.workflow.domain.PullRequest
import io.springkit.workflow.domain.PullRequestId
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseId
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.ReviewRevision
import io.springkit.workflow.domain.ReviewRevisionId
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.StartRequestKey
import io.springkit.workflow.domain.StartRequestRecord
import io.springkit.workflow.domain.SubTask
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.SubTaskState
import io.springkit.workflow.domain.SyncConflict
import io.springkit.workflow.domain.Task
import io.springkit.workflow.domain.TaskId
import io.springkit.workflow.domain.ThreadId
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.Workspace
import io.springkit.workflow.domain.WorkspaceId
import io.springkit.workflow.domain.WorkspacePath

/** 공급자 오류를 노출하지 않고 애플리케이션과 외부 포트가 공유하는 결과입니다. */
sealed interface PortResult<out T> {
  data class Success<T>(val value: T, val change: ChangeReceipt? = null) : PortResult<T>

  data class Failure(
      val error: PortError,
      val change: ChangeReceipt? = null,
  ) : PortResult<Nothing>
}

data class PortError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val target: String? = null,
)

/** 어댑터 또는 저장소 트랜잭션이 기록하는 되돌릴 수 있는 변경입니다. */
data class ChangeReceipt(
    val id: String,
    val operation: String,
    val status: ChangeStatus = ChangeStatus.APPLIED,
    val compensation: Compensation? = null,
    val beforeRevision: String? = null,
    val afterRevision: String? = null,
) {
  init {
    require(id.isNotBlank()) { "change receipt id must not be blank" }
    require(operation.isNotBlank()) { "change receipt operation must not be blank" }
    require(status != ChangeStatus.COMPENSATED || compensation != null) {
      "compensated change must retain its compensation"
    }
  }
}

enum class ChangeStatus {
  APPLIED,
  PENDING,
  COMPENSATED,
}

data class Compensation(
    val id: String,
    val operation: String,
    val idempotencyKey: String,
    val automatic: Boolean = true,
) {
  init {
    require(id.isNotBlank()) { "compensation id must not be blank" }
    require(operation.isNotBlank()) { "compensation operation must not be blank" }
    require(idempotencyKey.isNotBlank()) { "compensation idempotency key must not be blank" }
  }
}

data class PortPage<T>(val values: List<T>, val nextCursor: String? = null)

enum class StoreScope {
  ALL,
  TASK,
  SUBTASK,
  WORKSPACE,
  REVIEW,
  INTEGRATION,
  DEPLOYMENT,
  RELEASE,
}

data class WorkflowStoreSnapshot(
    val revision: String,
    val schemaVersion: Int = 1,
    val sequence: IdSequence = IdSequence(),
    val tasks: List<Task> = emptyList(),
    val subTasks: List<SubTask> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val pullRequests: List<PullRequest> = emptyList(),
    val checks: Map<SubTaskId, CheckSummary> = emptyMap(),
    val integrations: List<Integration> = emptyList(),
    val mergeQueue: List<MergeQueueEntry> = emptyList(),
    val candidates: List<DeploymentCandidate> = emptyList(),
    val releases: List<Release> = emptyList(),
    val startRequests: Map<StartRequestKey, StartRequestRecord> = emptyMap(),
    val syncConflicts: Map<SubTaskId, SyncConflict> = emptyMap(),
    val eventLog: EventLog = EventLog(),
) {
  init {
    require(revision.isNotBlank()) { "store revision must not be blank" }
    require(schemaVersion == 1) { "unsupported store snapshot schema version: $schemaVersion" }
  }
}

data class StoreSnapshotRequest(
    val scope: StoreScope = StoreScope.ALL,
    val taskId: TaskId? = null,
    val subTaskId: SubTaskId? = null,
    val candidateId: CandidateId? = null,
    val releaseId: ReleaseId? = null,
)

data class StoreSnapshotResponse(val snapshot: WorkflowStoreSnapshot)

data class StoreTransactionRequest(
    val transactionId: String,
    val expectedRevision: String? = null,
    val idempotencyKey: String? = null,
) {
  init {
    require(transactionId.isNotBlank()) { "transaction id must not be blank" }
  }
}

data class StoreTransactionResponse(
    val transactionId: String,
    val state: StoreTransactionState,
    val revision: String? = null,
)

enum class StoreTransactionState {
  OPEN,
  COMMITTED,
  ROLLED_BACK,
}

data class StoreWriteRequest(
    val transactionId: String,
    val expectedRevision: String,
    val snapshot: WorkflowStoreSnapshot,
)

data class StoreWriteResponse(val revision: String)

data class StoreEventRequest(
    val transactionId: String,
    val event: DomainEvent,
    val audit: AuditEntry? = null,
)

data class StoreEventResponse(val event: DomainEvent, val revision: String)

/** 영속화된 Workflow 상태와 트랜잭션 경계를 제공합니다. */
interface WorkflowStorePort : StoreTransactionPort {
  fun snapshot(request: StoreSnapshotRequest): PortResult<StoreSnapshotResponse>

  fun write(request: StoreWriteRequest): PortResult<StoreWriteResponse>

  fun append(request: StoreEventRequest): PortResult<StoreEventResponse>
}

interface StoreTransactionPort {
  fun begin(request: StoreTransactionRequest): PortResult<StoreTransactionResponse>

  fun commit(request: StoreTransactionRequest): PortResult<StoreTransactionResponse>

  fun rollback(request: StoreTransactionRequest): PortResult<StoreTransactionResponse>
}

data class TaskLookupRequest(
    val taskId: TaskId? = null,
    val externalId: ExternalTaskId? = null,
)

data class TaskLookupResponse(val task: Task)

data class SubTaskLookupRequest(val subTaskId: SubTaskId)

data class SubTaskLookupResponse(val subTask: SubTask)

data class CreateSubTaskRequest(
    val externalTaskId: ExternalTaskId,
    val title: String,
    val requestId: String,
    val requires: SubTaskId? = null,
)

data class CreateSubTaskResponse(
    val task: Task,
    val subTask: SubTask,
    override val change: ChangeReceipt,
) : ChangeResponse

data class UpdateExternalSubTaskRequest(
    val externalTaskId: ExternalTaskId,
    val subTaskId: SubTaskId,
    val state: SubTaskState,
    val requestId: String,
)

data class UpdateExternalSubTaskResponse(
    val externalTaskId: ExternalTaskId,
    val subTaskId: SubTaskId,
    val state: SubTaskState,
    override val change: ChangeReceipt,
) : ChangeResponse

/** 외부 작업 추적을 Workflow Store 상태와 분리합니다. */
interface TaskPort {
  fun get(request: TaskLookupRequest): PortResult<TaskLookupResponse>

  fun getSubTask(request: SubTaskLookupRequest): PortResult<SubTaskLookupResponse>

  fun createSubTask(request: CreateSubTaskRequest): PortResult<CreateSubTaskResponse>

  fun updateSubTask(
      request: UpdateExternalSubTaskRequest
  ): PortResult<UpdateExternalSubTaskResponse>
}

data class WorkspaceLookupRequest(
    val workspaceId: WorkspaceId? = null,
    val subTaskId: SubTaskId? = null,
    val path: WorkspacePath? = null,
)

data class ContentSnapshot(
    val revision: String,
    val fingerprint: String,
    val dirty: Boolean,
    val files: List<String> = emptyList(),
) {
  init {
    require(revision.isNotBlank()) { "content revision must not be blank" }
    require(fingerprint.isNotBlank()) { "content fingerprint must not be blank" }
  }
}

data class WorkspaceLookupResponse(
    val workspace: Workspace,
    val content: ContentSnapshot? = null,
)

data class CreateWorkspaceRequest(
    val workspaceId: WorkspaceId,
    val subTaskId: SubTaskId,
    val branch: BranchName,
    val baseRevision: String,
    val path: WorkspacePath,
)

data class CreateWorkspaceResponse(
    val workspace: Workspace,
    override val change: ChangeReceipt,
) : ChangeResponse

data class DeleteWorkspaceRequest(val workspaceId: WorkspaceId, val subTaskId: SubTaskId)

data class DeleteWorkspaceResponse(
    val workspaceId: WorkspaceId,
    override val change: ChangeReceipt,
) : ChangeResponse

data class InspectContentRequest(
    val workspaceId: WorkspaceId,
    val expectedRevision: String? = null,
)

data class InspectContentResponse(val content: ContentSnapshot)

interface WorkspacePort {
  fun get(request: WorkspaceLookupRequest): PortResult<WorkspaceLookupResponse>

  fun create(request: CreateWorkspaceRequest): PortResult<CreateWorkspaceResponse>

  fun delete(request: DeleteWorkspaceRequest): PortResult<DeleteWorkspaceResponse>
}

interface ContentPort {
  fun inspect(request: InspectContentRequest): PortResult<InspectContentResponse>
}

data class MainRevisionRequest(val remote: String = "origin", val branch: BranchName = "main")

data class MainRevisionResponse(val revision: MainRevision)

data class GitStatus(
    val revision: String,
    val fingerprint: String,
    val dirty: Boolean,
    val conflicts: List<String> = emptyList(),
) {
  init {
    require(revision.isNotBlank()) { "git revision must not be blank" }
    require(fingerprint.isNotBlank()) { "git fingerprint must not be blank" }
  }
}

data class GitInspectRequest(val workspaceId: WorkspaceId)

data class GitInspectResponse(val status: GitStatus)

data class CreateBranchRequest(
    val branch: BranchName,
    val baseBranch: BranchName,
    val baseRevision: String,
)

data class CreateBranchResponse(
    val branch: BranchName,
    val revision: String,
    override val change: ChangeReceipt,
) : ChangeResponse

data class CreateWorktreeRequest(
    val workspaceId: WorkspaceId,
    val branch: BranchName,
    val path: WorkspacePath,
)

data class CreateWorktreeResponse(
    val workspaceId: WorkspaceId,
    val branch: BranchName,
    val path: WorkspacePath,
    override val change: ChangeReceipt,
) : ChangeResponse

data class RestackRequest(
    val workspaceId: WorkspaceId,
    val branch: BranchName,
    val baseBranch: BranchName,
    val baseRevision: String,
    val expectedRevision: String,
)

data class RestackResponse(
    val revision: String,
    val fingerprint: String,
    val diffChanged: Boolean,
    val conflicts: List<String> = emptyList(),
    override val change: ChangeReceipt,
) : ChangeResponse

data class ContinueRestackRequest(val workspaceId: WorkspaceId)

data class ContinueRestackResponse(override val change: ChangeReceipt) : ChangeResponse

data class AbortRestackRequest(val workspaceId: WorkspaceId)

data class AbortRestackResponse(override val change: ChangeReceipt) : ChangeResponse

data class RemoveBranchRequest(val branch: BranchName, val expectedRevision: String? = null)

data class RemoveBranchResponse(val branch: BranchName, override val change: ChangeReceipt) :
    ChangeResponse

interface GitPort {
  fun refreshMain(request: MainRevisionRequest): PortResult<MainRevisionResponse>

  fun inspect(request: GitInspectRequest): PortResult<GitInspectResponse>

  fun createBranch(request: CreateBranchRequest): PortResult<CreateBranchResponse>

  fun createWorktree(request: CreateWorktreeRequest): PortResult<CreateWorktreeResponse>

  fun restack(request: RestackRequest): PortResult<RestackResponse>

  fun continueRestack(request: ContinueRestackRequest): PortResult<ContinueRestackResponse> =
      PortResult.Failure(
          PortError(
              code = "GIT_RECOVERY_UNSUPPORTED",
              message = "Git rebase 복구 동작을 지원하지 않습니다.",
              target = request.workspaceId,
          )
      )

  fun abortRestack(request: AbortRestackRequest): PortResult<AbortRestackResponse> =
      PortResult.Failure(
          PortError(
              code = "GIT_RECOVERY_UNSUPPORTED",
              message = "Git rebase 복구 동작을 지원하지 않습니다.",
              target = request.workspaceId,
          )
      )

  fun removeBranch(request: RemoveBranchRequest): PortResult<RemoveBranchResponse>
}

data class OpenReviewRequest(
    val subTaskId: SubTaskId,
    val title: String,
    val body: String,
    val base: BranchName,
    val branch: BranchName,
    val risk: Risk,
    val exposure: Exposure,
    val featureFlagId: FeatureFlagId? = null,
    val changeRevision: ChangeRevision,
    val reviewRevision: ReviewRevision,
)

data class OpenReviewResponse(
    val pullRequest: PullRequest,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetReviewRequest(val pullRequestId: PullRequestId)

data class GetReviewResponse(val pullRequest: PullRequest)

data class UpdateReviewRequest(
    val pullRequestId: PullRequestId,
    val expectedReviewRevisionId: ReviewRevisionId,
    val body: String? = null,
    val changeRevision: ChangeRevision? = null,
    val reviewRevision: ReviewRevision? = null,
)

data class UpdateReviewResponse(
    val pullRequest: PullRequest,
    val codeChanged: Boolean,
    override val change: ChangeReceipt,
) : ChangeResponse

data class AddReviewCommentRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val author: Actor,
    val level: ReviewLevel,
    val comment: ReviewComment,
)

data class AddReviewCommentResponse(
    val reviewRevision: ReviewRevision,
    val threadId: ThreadId,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ReplyReviewThreadRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val threadId: ThreadId,
    val comment: ReviewComment,
)

data class ReplyReviewThreadResponse(
    val reviewRevision: ReviewRevision,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ResolveReviewThreadRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val threadId: ThreadId,
    val actor: Actor,
)

data class ResolveReviewThreadResponse(
    val reviewRevision: ReviewRevision,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ReadyReviewRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val actor: Actor,
)

data class ReadyReviewResponse(
    val pullRequest: PullRequest,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ApproveReviewRequest(
    val pullRequestId: PullRequestId,
    val changeRevisionId: ChangeRevisionId,
    val diffIdentity: String,
    val actor: Actor,
)

data class ApproveReviewResponse(
    val pullRequest: PullRequest,
    val approval: Approval,
    override val change: ChangeReceipt,
) : ChangeResponse

interface ReviewPort {
  fun open(request: OpenReviewRequest): PortResult<OpenReviewResponse>

  fun get(request: GetReviewRequest): PortResult<GetReviewResponse>

  fun update(request: UpdateReviewRequest): PortResult<UpdateReviewResponse>

  fun comment(request: AddReviewCommentRequest): PortResult<AddReviewCommentResponse>

  fun reply(request: ReplyReviewThreadRequest): PortResult<ReplyReviewThreadResponse>

  fun resolve(request: ResolveReviewThreadRequest): PortResult<ResolveReviewThreadResponse>

  fun ready(request: ReadyReviewRequest): PortResult<ReadyReviewResponse>

  fun approve(request: ApproveReviewRequest): PortResult<ApproveReviewResponse>
}

data class RunValidationRequest(
    val workspaceId: WorkspaceId,
    val revision: String,
    val fingerprint: String,
    val required: List<Validation> = emptyList(),
)

data class RunValidationResponse(
    val validations: List<Validation>,
    val summary: CheckSummary? = null,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetValidationRequest(
    val workspaceId: WorkspaceId? = null,
    val revision: String,
    val fingerprint: String? = null,
)

data class GetValidationResponse(val validations: List<Validation>, val checks: List<CheckResult>)

interface ValidationPort {
  fun run(request: RunValidationRequest): PortResult<RunValidationResponse>

  fun get(request: GetValidationRequest): PortResult<GetValidationResponse>
}

data class StartCiRequest(
    val pullRequestId: PullRequestId,
    val revision: String,
    val checks: List<Validation> = emptyList(),
)

data class StartCiResponse(
    val run: CiRun,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetCiRequest(val pullRequestId: PullRequestId, val revision: String? = null)

data class GetCiResponse(val runs: List<CiRun>, val status: CiStatus)

interface CiPort {
  fun start(request: StartCiRequest): PortResult<StartCiResponse>

  fun get(request: GetCiRequest): PortResult<GetCiResponse>
}

data class StartAiReviewRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
    val changeRevisionId: ChangeRevisionId,
    val diff: Diff,
)

data class StartAiReviewResponse(
    val status: AiReviewStatus,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetAiReviewRequest(
    val pullRequestId: PullRequestId,
    val reviewRevisionId: ReviewRevisionId,
)

data class GetAiReviewResponse(
    val status: AiReviewStatus,
    val comments: List<ReviewComment> = emptyList(),
)

interface AiReviewPort {
  fun start(request: StartAiReviewRequest): PortResult<StartAiReviewResponse>

  fun get(request: GetAiReviewRequest): PortResult<GetAiReviewResponse>
}

data class EnqueueMergeRequest(
    val subTaskId: SubTaskId,
    val pullRequestId: PullRequestId,
    val changeRevisionId: ChangeRevisionId,
    val expectedRevision: String,
)

data class EnqueueMergeResponse(
    val entry: MergeQueueEntry,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetMergeQueueRequest(
    val subTaskId: SubTaskId? = null,
    val pullRequestId: PullRequestId? = null,
)

data class GetMergeQueueResponse(val entries: List<MergeQueueEntry>)

data class MergeQueueMergeRequest(
    val entryId: String,
    val expectedChangeRevisionId: ChangeRevisionId,
)

data class MergeQueueMergeResponse(
    val integration: Integration,
    override val change: ChangeReceipt,
) : ChangeResponse

interface MergeQueuePort {
  fun enqueue(request: EnqueueMergeRequest): PortResult<EnqueueMergeResponse>

  fun get(request: GetMergeQueueRequest): PortResult<GetMergeQueueResponse>

  fun merge(request: MergeQueueMergeRequest): PortResult<MergeQueueMergeResponse>
}

data class CreateCandidateRequest(
    val mainRevision: MainRevision,
    val includedSubTasks: List<SubTaskId>,
    val risks: Map<SubTaskId, Risk>,
)

data class CreateCandidateResponse(
    val candidate: DeploymentCandidate,
    override val change: ChangeReceipt,
) : ChangeResponse

data class GetCandidateRequest(val candidateId: CandidateId)

data class GetCandidateResponse(val candidate: DeploymentCandidate)

data class ValidateCandidateRequest(val candidateId: CandidateId, val mainRevision: MainRevision)

data class ValidateCandidateResponse(
    val candidate: DeploymentCandidate,
    override val change: ChangeReceipt,
) : ChangeResponse

data class StartCanaryRequest(val candidateId: CandidateId, val actor: Actor? = null)

data class StartCanaryResponse(
    val candidate: DeploymentCandidate,
    override val change: ChangeReceipt,
) : ChangeResponse

data class PromoteProductionRequest(
    val candidateId: CandidateId,
    val expectedState: DeploymentCandidateState,
)

data class PromoteProductionResponse(
    val candidate: DeploymentCandidate,
    override val change: ChangeReceipt,
) : ChangeResponse

interface DeploymentPort {
  fun createCandidate(request: CreateCandidateRequest): PortResult<CreateCandidateResponse>

  fun getCandidate(request: GetCandidateRequest): PortResult<GetCandidateResponse>

  fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse>

  fun startCanary(request: StartCanaryRequest): PortResult<StartCanaryResponse>

  fun promoteProduction(request: PromoteProductionRequest): PortResult<PromoteProductionResponse>
}

data class GetReleaseRequest(val releaseId: ReleaseId? = null, val candidateId: CandidateId? = null)

data class GetReleaseResponse(val releases: List<Release>)

data class CreateReleaseRequest(
    val releaseId: ReleaseId,
    val candidateId: CandidateId,
    val featureFlagId: FeatureFlagId,
)

data class CreateReleaseResponse(
    val release: Release,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ValidateReleaseRequest(val releaseId: ReleaseId, val actor: Actor? = null)

data class ValidateReleaseResponse(
    val release: Release,
    override val change: ChangeReceipt,
) : ChangeResponse

data class StartReleaseRequest(val releaseId: ReleaseId, val actor: Actor)

data class StartReleaseResponse(
    val release: Release,
    override val change: ChangeReceipt,
) : ChangeResponse

data class ContinueReleaseRequest(val releaseId: ReleaseId)

data class ContinueReleaseResponse(
    val release: Release,
    override val change: ChangeReceipt,
) : ChangeResponse

interface ReleasePort {
  fun get(request: GetReleaseRequest): PortResult<GetReleaseResponse>

  fun create(request: CreateReleaseRequest): PortResult<CreateReleaseResponse>

  fun validateInternal(request: ValidateReleaseRequest): PortResult<ValidateReleaseResponse>

  fun start(request: StartReleaseRequest): PortResult<StartReleaseResponse>

  fun continueRollout(request: ContinueReleaseRequest): PortResult<ContinueReleaseResponse>
}

enum class Capability {
  READ,
  WRITE,
  READY,
  APPROVE,
  DEPLOY,
  RELEASE,
}

data class CurrentActorRequest(val invocationId: String? = null)

data class CurrentActorResponse(val actor: Actor)

data class AuthorizeRequest(
    val actor: Actor,
    val capability: Capability,
    val target: String,
)

data class AuthorizeResponse(val allowed: Boolean, val reason: String? = null)

interface IdentityPort {
  fun currentActor(
      request: CurrentActorRequest = CurrentActorRequest()
  ): PortResult<CurrentActorResponse>

  fun authorize(request: AuthorizeRequest): PortResult<AuthorizeResponse>
}

data class NowRequest(val requestId: String? = null)

data class NowResponse(val epochMillis: Long)

interface ClockPort {
  fun now(request: NowRequest = NowRequest()): PortResult<NowResponse>
}

enum class IdKind {
  TASK,
  SUBTASK,
  WORKSPACE,
  BRANCH,
  PULL_REQUEST,
  REVIEW_REVISION,
  CHANGE_REVISION,
  THREAD,
  VALIDATION,
  CHECK,
  MERGE_QUEUE,
  CANDIDATE,
  RELEASE,
  FEATURE_FLAG,
  TRANSACTION,
  EVENT,
  AUDIT,
  COMPENSATION,
}

data class IssueIdRequest(
    val kind: IdKind,
    val requestId: String? = null,
    val count: Int = 1,
) {
  init {
    require(count > 0) { "id count must be positive" }
  }
}

data class IssuedId(val kind: IdKind, val value: String)

data class IssueIdResponse(val ids: List<IssuedId>)

interface IdPort {
  fun issue(request: IssueIdRequest): PortResult<IssueIdResponse>
}

data class CompensateRequest(
    val change: ChangeReceipt,
    val reason: String,
) {
  init {
    require(reason.isNotBlank()) { "compensation reason must not be blank" }
  }
}

data class CompensateResponse(val change: ChangeReceipt)

/** 이전 변경 포트 응답에 포함된 보상 계획을 실행합니다. */
interface CompensationPort {
  fun compensate(request: CompensateRequest): PortResult<CompensateResponse>
}

enum class ExternalEventKind {
  TASK_CHANGED,
  REVIEW_CHANGED,
  CI_CHANGED,
  AI_REVIEW_CHANGED,
  MERGE_QUEUE_CHANGED,
  MAIN_MERGED,
  DEPLOYMENT_CHANGED,
  CANARY_CHANGED,
  RELEASE_CHANGED,
}

data class ExternalEvent(
    val id: String,
    val kind: ExternalEventKind,
    val targetId: String,
    val occurredAtEpochMillis: Long,
    val attributes: Map<String, String> = emptyMap(),
) {
  init {
    require(id.isNotBlank()) { "external event id must not be blank" }
    require(targetId.isNotBlank()) { "external event target must not be blank" }
  }
}

data class ReceiveEventRequest(val event: ExternalEvent)

data class ReceiveEventResponse(val event: ExternalEvent, val accepted: Boolean)

data class AcknowledgeEventRequest(
    val eventId: String,
    val accepted: Boolean,
    val message: String? = null,
)

data class AcknowledgeEventResponse(val eventId: String)

/** 인바운드 이벤트를 공급자와 무관하게 멱등적으로 전달합니다. */
interface WorkflowEventPort {
  fun receive(request: ReceiveEventRequest): PortResult<ReceiveEventResponse>

  fun acknowledge(request: AcknowledgeEventRequest): PortResult<AcknowledgeEventResponse>
}

interface ChangeResponse {
  val change: ChangeReceipt
}
