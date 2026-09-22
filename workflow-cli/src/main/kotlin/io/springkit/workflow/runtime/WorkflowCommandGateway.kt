package io.springkit.workflow.runtime

import io.springkit.workflow.adapter.cli.WorkflowCommandGateway as CliWorkflowCommandGateway
import io.springkit.workflow.adapter.cli.WorkflowCommandRequest
import io.springkit.workflow.adapter.json.WorkflowJson
import io.springkit.workflow.adapter.local.OkioWorkflowBodyReader
import io.springkit.workflow.application.AddReviewCommentRequest
import io.springkit.workflow.application.ApproveGateRequest
import io.springkit.workflow.application.CheckRequest
import io.springkit.workflow.application.DeliveryGateUseCases
import io.springkit.workflow.application.DeployGateRequest
import io.springkit.workflow.application.GetReviewRequest
import io.springkit.workflow.application.OpenReviewLifecycleRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReadyGateRequest
import io.springkit.workflow.application.ReleaseGateRequest
import io.springkit.workflow.application.ReplyReviewThreadRequest
import io.springkit.workflow.application.ResolveReviewThreadRequest
import io.springkit.workflow.application.ReviewGateUseCases
import io.springkit.workflow.application.ReviewLifecycleUseCases
import io.springkit.workflow.application.ReviewUseCases
import io.springkit.workflow.application.StackRequest
import io.springkit.workflow.application.StackSyncUseCases
import io.springkit.workflow.application.StartCheckUseCases
import io.springkit.workflow.application.StartRequest
import io.springkit.workflow.application.StatusRequest
import io.springkit.workflow.application.StatusUseCase
import io.springkit.workflow.application.SyncRequest
import io.springkit.workflow.application.UpdateReviewLifecycleRequest
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.Exposure
import io.springkit.workflow.domain.ExternalTaskId
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.ReviewComment
import io.springkit.workflow.domain.ReviewLevel
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.WorkflowResult
import io.springkit.workflow.domain.WorkspacePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** CLI 요청을 Application 유스케이스로 변환하고 공통 JSON 결과로 되돌리는 런타임 디스패처입니다. */
class WorkflowCommandGateway(
    private val startCheck: StartCheckUseCases,
    private val review: ReviewUseCases,
    private val reviewLifecycle: ReviewLifecycleUseCases,
    private val stackSync: StackSyncUseCases,
    private val status: StatusUseCase,
    private val reviewGate: ReviewGateUseCases,
    private val deliveryGate: DeliveryGateUseCases,
    private val context: WorkflowRuntimeContextResolver,
    private val bodyReader: WorkflowBodyReader = OkioWorkflowBodyReader(),
    private val commentId: (String) -> PortResult<String> = { revision ->
      PortResult.Success("comment-$revision")
    },
) : CliWorkflowCommandGateway {
  override fun execute(request: WorkflowCommandRequest): WorkflowResult<JsonObject> =
      try {
        when (request) {
          is WorkflowCommandRequest.Start -> start(request)
          WorkflowCommandRequest.Check -> check()
          is WorkflowCommandRequest.ReviewOpen -> reviewOpen(request)
          is WorkflowCommandRequest.ReviewShow -> reviewShow(request)
          is WorkflowCommandRequest.ReviewUpdate -> reviewUpdate(request)
          is WorkflowCommandRequest.ReviewComment -> reviewComment(request)
          is WorkflowCommandRequest.ReviewReply -> reviewReply(request)
          is WorkflowCommandRequest.ReviewResolve -> reviewResolve(request)
          is WorkflowCommandRequest.Stack -> stack(request)
          is WorkflowCommandRequest.Sync -> sync(request)
          is WorkflowCommandRequest.Status -> status(request)
          is WorkflowCommandRequest.GateReady -> gateReady(request)
          is WorkflowCommandRequest.GateApprove -> gateApprove(request)
          is WorkflowCommandRequest.GateDeploy -> gateDeploy(request)
          is WorkflowCommandRequest.GateRelease -> gateRelease(request)
        }
      } catch (failure: InvalidWorkflowCommandArgument) {
        failure(FailureCode.INVALID_ARGUMENT, failure.message ?: "명령 인자를 처리할 수 없습니다.")
      } catch (failure: Exception) {
        failure(
            FailureCode.INVARIANT_VIOLATION,
            "명령 실행 중 예상하지 못한 오류가 발생했습니다: " + (failure.message ?: failure::class.simpleName),
        )
      }

  private fun start(request: WorkflowCommandRequest.Start) =
      startCheck
          .start(
              StartRequest(
                  taskId = ExternalTaskId(request.task),
                  requestId = request.requestId,
                  title = request.title,
                  requires = request.requires,
              )
          )
          .toJson { data ->
            buildJsonObject {
              put("task", data.task.externalId.value)
              put("subtask", data.subTask.id)
              put("branch", data.subTask.branch)
              put("state", data.subTask.state.name)
              put(
                  "workspace",
                  buildJsonObject { put("path", data.workspace.path.value) },
              )
              put(
                  "next",
                  kotlinx.serialization.json.buildJsonArray {
                    add(nextJson(NextAction(ActorKind.AGENT, "implement_change")))
                  },
              )
            }
          }

  private fun check() =
      context
          .currentWorkspaceId()
          .flatMapResult { workspaceId ->
            context.currentSubTaskId().flatMapResult { subTaskId ->
              context.currentWorkspacePath().flatMapResult { path ->
                WorkflowResult.Success(startCheck.check(CheckRequest(workspaceId, subTaskId, path)))
              }
            }
          }
          .flatten()
          .toJson { data ->
            buildJsonObject {
              put(
                  "workspace",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.Workspace.serializer(),
                      data.workspace,
                  ),
              )
              put("revision", data.revision)
              put("fingerprint", data.fingerprint)
              put(
                  "validations",
                  WorkflowJson.format.encodeToJsonElement(
                      kotlinx.serialization.builtins.ListSerializer(
                          io.springkit.workflow.domain.Validation.serializer()
                      ),
                      data.validations,
                  ),
              )
              put(
                  "summary",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.CheckSummary.serializer(),
                      data.summary,
                  ),
              )
              put("publishable", data.publishable)
            }
          }

  private fun reviewOpen(request: WorkflowCommandRequest.ReviewOpen) =
      context
          .currentWorkspaceId()
          .flatMapResult { workspaceId ->
            context.currentSubTaskId().flatMapResult { subTaskId ->
              context.currentWorkspacePath().flatMapResult { workspacePath ->
                readBody(workspacePath, request.bodyFile).flatMap { body ->
                  reviewLifecycle.open(
                      OpenReviewLifecycleRequest(
                          workspaceId = workspaceId,
                          subTaskId = subTaskId,
                          body = body,
                          risk = request.risk.toRisk(),
                          exposure = request.exposure.toExposure(),
                          featureFlagId = request.featureFlag,
                      )
                  )
                }
              }
            }
          }
          .toJson { data ->
            buildJsonObject {
              put(
                  "pull_request",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.PullRequest.serializer(),
                      data.pullRequest,
                  ),
              )
              put(
                  "check",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.CheckSummary.serializer(),
                      data.check,
                  ),
              )
              put("published_revision", data.publishedRevision)
            }
          }

  private fun reviewShow(request: WorkflowCommandRequest.ReviewShow) =
      context
          .currentReview()
          .flatMapResult { current ->
            review.show(GetReviewRequest(current.pullRequestId)).flatMap { shown ->
              status.status(StatusRequest(subTaskId = shown.pullRequest.subTaskId)).map {
                  currentStatus ->
                ReviewShowView(shown.pullRequest, currentStatus)
              }
            }
          }
          .toJson { data ->
            val pullRequest = data.pullRequest
            val reviewRevision = pullRequest.reviewRevision
            val listedThreads =
                if (request.threads == "all") reviewRevision.threads
                else reviewRevision.threads.filter { it.isOpen }
            buildJsonObject {
              put("subtask", pullRequest.subTaskId)
              put("state", pullRequest.state.name)
              put("pr", pullRequestJson(pullRequest))
              put("review_revision", reviewRevision.id)
              put("change_revision", pullRequest.changeRevision.id)
              put("diff_identity", pullRequest.changeRevision.diff.identity)
              put("risk", pullRequest.risk.name)
              put("exposure", pullRequest.exposure.name)
              put("ci", pullRequest.ci.name)
              put("ai_review", pullRequest.aiReview.name)
              put("ready", pullRequest.state != io.springkit.workflow.domain.PullRequestState.DRAFT)
              pullRequest.approval?.let {
                put(
                    "approval",
                    WorkflowJson.format.encodeToJsonElement(
                        io.springkit.workflow.domain.Approval.serializer(),
                        it,
                    ),
                )
              }
              put(
                  "approval_applicable",
                  pullRequest.approval?.let {
                    it.active &&
                        it.changeRevisionId == pullRequest.changeRevision.id &&
                        it.diffIdentity == pullRequest.changeRevision.diff.identity
                  } ?: false,
              )
              put(
                  "threads",
                  WorkflowJson.format.encodeToJsonElement(
                      kotlinx.serialization.builtins.ListSerializer(
                          io.springkit.workflow.domain.ReviewThread.serializer()
                      ),
                      listedThreads,
                  ),
              )
              put(
                  "blocked_by",
                  kotlinx.serialization.json.buildJsonArray {
                    data.status.blockedBy.forEach { blocker ->
                      add(
                          buildJsonObject {
                            put("code", blocker.code)
                            put("message", blocker.message)
                            blocker.target?.let { put("target", it) }
                          }
                      )
                    }
                  },
              )
              put(
                  "next",
                  kotlinx.serialization.json.buildJsonArray {
                    data.status.next.forEach { add(nextJson(it)) }
                  },
              )
              if (request.diff) {
                put(
                    "diff",
                    WorkflowJson.format.encodeToJsonElement(
                        io.springkit.workflow.domain.Diff.serializer(),
                        pullRequest.changeRevision.diff,
                    ),
                )
              }
            }
          }

  private fun reviewUpdate(request: WorkflowCommandRequest.ReviewUpdate) =
      context
          .currentReview()
          .flatMapResult { current ->
            context.currentWorkspaceId().flatMapResult { workspaceId ->
              context.currentSubTaskId().flatMapResult { subTaskId ->
                context.currentWorkspacePath().flatMapResult { workspacePath ->
                  optionalBody(workspacePath, request.bodyFile).flatMap { body ->
                    reviewLifecycle.update(
                        UpdateReviewLifecycleRequest(
                            workspaceId = workspaceId,
                            subTaskId = subTaskId,
                            pullRequestId = current.pullRequestId,
                            expectedReviewRevisionId = request.revision,
                            body = body,
                        )
                    )
                  }
                }
              }
            }
          }
          .toJson { data ->
            buildJsonObject {
              put(
                  "pull_request",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.PullRequest.serializer(),
                      data.pullRequest,
                  ),
              )
              put("code_changed", data.codeChanged)
              put(
                  "check",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.CheckSummary.serializer(),
                      data.check,
                  ),
              )
              data.publishedRevision?.let { put("published_revision", it) }
            }
          }

  private fun reviewComment(request: WorkflowCommandRequest.ReviewComment) =
      context
          .currentReview()
          .flatMapResult { current ->
            context.currentWorkspacePath().flatMapResult { workspacePath ->
              body(workspacePath, request.body, request.bodyFile).flatMap { text ->
                context.currentActor(current.reviewRevision.id).flatMapResult { actor ->
                  commentId(request.revision).flatMapResult { id ->
                    review.comment(
                        AddReviewCommentRequest(
                            current.pullRequestId,
                            request.revision,
                            actor,
                            request.level.toReviewLevel(),
                            ReviewComment(
                                id,
                                actor,
                                text,
                                path = request.path,
                                line = request.line,
                            ),
                        ),
                    )
                  }
                }
              }
            }
          }
          .toJson { data ->
            buildJsonObject {
              put(
                  "review_revision",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.ReviewRevision.serializer(),
                      data.reviewRevision,
                  ),
              )
              put("thread", data.threadId)
              put("change", changeJson(data.change))
            }
          }

  private fun reviewReply(request: WorkflowCommandRequest.ReviewReply) =
      context
          .currentReview()
          .flatMapResult { current ->
            context.currentWorkspacePath().flatMapResult { workspacePath ->
              body(workspacePath, request.body, request.bodyFile).flatMap { text ->
                context.currentActor(current.reviewRevision.id).flatMapResult { actor ->
                  commentId(request.thread).flatMapResult { id ->
                    review.reply(
                        ReplyReviewThreadRequest(
                            current.pullRequestId,
                            request.revision,
                            request.thread,
                            ReviewComment(id, actor, text),
                        )
                    )
                  }
                }
              }
            }
          }
          .toJson { data ->
            buildJsonObject {
              put(
                  "review_revision",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.ReviewRevision.serializer(),
                      data.reviewRevision,
                  ),
              )
              put("change", changeJson(data.change))
            }
          }

  private fun reviewResolve(request: WorkflowCommandRequest.ReviewResolve) =
      context
          .currentReview()
          .flatMapResult { current ->
            context.currentActor(current.reviewRevision.id).flatMapResult { actor ->
              review.resolve(
                  ResolveReviewThreadRequest(
                      current.pullRequestId,
                      request.revision,
                      request.thread,
                      actor,
                  )
              )
            }
          }
          .toJson { data ->
            buildJsonObject {
              put(
                  "review_revision",
                  WorkflowJson.format.encodeToJsonElement(
                      io.springkit.workflow.domain.ReviewRevision.serializer(),
                      data.reviewRevision,
                  ),
              )
              put("change", changeJson(data.change))
            }
          }

  private fun stack(request: WorkflowCommandRequest.Stack) =
      context
          .currentSubTaskId()
          .map { id ->
            stackSync.stack(StackRequest(id, request.requires, request.clear))
          }
          .flatten()
          .toJson(::stackJson)

  private fun sync(request: WorkflowCommandRequest.Sync) =
      context
          .currentSubTaskId()
          .map { id ->
            stackSync.sync(SyncRequest(id, request.continueSync, request.abort))
          }
          .flatten()
          .toJson(::syncJson)

  private fun status(request: WorkflowCommandRequest.Status) =
      status
          .status(
              StatusRequest(
                  subTaskId = request.subtask,
                  taskId = request.task,
                  candidateId = request.candidate,
                  releaseId = request.release,
              )
          )
          .toJson(::statusJson)

  private fun gateReady(request: WorkflowCommandRequest.GateReady) =
      reviewGate.ready(ReadyGateRequest(request.target, request.reviewRevision)).toJson { data ->
        buildJsonObject {
          put(
              "subtask",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.SubTask.serializer(),
                  data.subTask,
              ),
          )
          put(
              "pull_request",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.PullRequest.serializer(),
                  data.pullRequest,
              ),
          )
          put(
              "audit",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.AuditEntry.serializer(),
                  data.audit,
              ),
          )
        }
      }

  private fun gateApprove(request: WorkflowCommandRequest.GateApprove) =
      reviewGate.approve(ApproveGateRequest(request.target, request.changeRevision)).toJson { data
        ->
        buildJsonObject {
          put(
              "subtask",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.SubTask.serializer(),
                  data.subTask,
              ),
          )
          put(
              "pull_request",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.PullRequest.serializer(),
                  data.pullRequest,
              ),
          )
          put(
              "approval",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.Approval.serializer(),
                  data.approval,
              ),
          )
          put(
              "merge_queue",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.MergeQueueEntry.serializer(),
                  data.mergeQueue,
              ),
          )
          put(
              "audit",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.AuditEntry.serializer(),
                  data.audit,
              ),
          )
        }
      }

  private fun gateDeploy(request: WorkflowCommandRequest.GateDeploy) =
      deliveryGate.deploy(DeployGateRequest(request.target)).toJson { data ->
        buildJsonObject {
          put(
              "candidate",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.DeploymentCandidate.serializer(),
                  data.candidate,
              ),
          )
          put("change", changeJson(data.change))
        }
      }

  private fun gateRelease(request: WorkflowCommandRequest.GateRelease) =
      deliveryGate.release(ReleaseGateRequest(request.target)).toJson { data ->
        buildJsonObject {
          put(
              "release",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.Release.serializer(),
                  data.release,
              ),
          )
          put("change", changeJson(data.change))
        }
      }

  private fun body(
      workspacePath: WorkspacePath,
      value: String?,
      file: String?,
  ): WorkflowResult<String> =
      if (value != null) WorkflowResult.Success(value) else readBody(workspacePath, file!!)

  private fun optionalBody(
      workspacePath: WorkspacePath,
      file: String?,
  ): WorkflowResult<String?> =
      if (file == null) WorkflowResult.Success(null) else readBody(workspacePath, file).map { it }

  private fun readBody(workspacePath: WorkspacePath, path: String): WorkflowResult<String> =
      try {
        val managedPath = ManagedWorkspaceBodyPath.resolve(workspacePath, path)
        WorkflowResult.Success(bodyReader.read(managedPath.toString()))
      } catch (failure: Exception) {
        this.failure(FailureCode.INVALID_ARGUMENT, "본문 파일을 읽을 수 없습니다: ${failure.message ?: path}")
      }

  private fun changeJson(change: io.springkit.workflow.application.ChangeReceipt) =
      buildJsonObject {
        put("id", change.id)
        put("operation", change.operation)
        change.beforeRevision?.let { put("before_revision", it) }
        change.afterRevision?.let { put("after_revision", it) }
        put("status", change.status.name)
      }

  private fun stackJson(data: io.springkit.workflow.application.StackResponse) =
      stackResponseJson(data)

  private fun syncJson(data: io.springkit.workflow.application.SyncResponse) =
      syncResponseJson(data)

  private fun statusJson(data: io.springkit.workflow.application.StatusResponse): JsonObject =
      buildJsonObject {
        data.task?.let {
          put("task", it.externalId.value)
        }
        if (data.subTasks.isNotEmpty()) {
          put(
              "subtasks",
              kotlinx.serialization.json.buildJsonArray {
                data.subTasks.forEach { add(statusJson(it)) }
              },
          )
        }
        data.subTask?.let {
          put("subtask", it.id)
          put("branch", it.branch)
          put("state", it.state.name)
          it.requires?.let { requires -> put("requires", requires) }
        }
        data.workspace?.let { workspace ->
          put(
              "workspace",
              buildJsonObject {
                put("path", workspace.path)
                workspace.content?.let { put("content", contentJson(it)) }
                workspace.git?.let { put("git", gitJson(it)) }
              },
          )
        }
        data.review?.let { put("review", statusReviewJson(it)) }
        data.integration?.let { put("integration", statusIntegrationJson(it)) }
        put(
            "deployment",
            data.deployment?.let(::statusDeploymentJson)
                ?: buildJsonObject { put("state", "NOT_SELECTED") },
        )
        put(
            "release",
            data.release?.let(::statusReleaseJson)
                ?: buildJsonObject { put("state", "NOT_APPLICABLE") },
        )
        put(
            "blocked_by",
            kotlinx.serialization.json.buildJsonArray {
              data.blockedBy.forEach {
                add(
                    buildJsonObject {
                      put("code", it.code)
                      put("message", it.message)
                      it.target?.let { target -> put("target", target) }
                    }
                )
              }
            },
        )
        put(
            "next",
            kotlinx.serialization.json.buildJsonArray {
              data.next.forEach { add(nextJson(it)) }
            },
        )
      }

  private fun pullRequestJson(pullRequest: io.springkit.workflow.domain.PullRequest) =
      buildJsonObject {
        put("id", pullRequest.id)
        put("title", pullRequest.title)
        put("body", pullRequest.body)
        put("base", pullRequest.base)
        put("state", pullRequest.state.name)
      }

  private fun statusReviewJson(review: io.springkit.workflow.application.StatusReview) =
      buildJsonObject {
        put("state", review.state.name)
        put("review_revision", review.reviewRevision)
        put("change_revision", review.changeRevision)
        put("diff_identity", review.diffIdentity)
        put("risk", review.risk.name)
        put("exposure", review.exposure.name)
        review.featureFlagId?.let { put("feature_flag_id", it) }
        put(
            "threads",
            WorkflowJson.format.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(
                    io.springkit.workflow.domain.ReviewThread.serializer()
                ),
                review.threads,
            ),
        )
        put("ready", review.ready)
        review.approval?.let {
          put(
              "approval",
              WorkflowJson.format.encodeToJsonElement(
                  io.springkit.workflow.domain.Approval.serializer(),
                  it,
              ),
          )
        }
        put("ci", review.ci.name)
        put("ai_review", review.aiReview.name)
      }

  private fun statusIntegrationJson(
      integration: io.springkit.workflow.application.StatusIntegration
  ) = buildJsonObject {
    put("state", integration.state.name)
    integration.mergeQueue?.let {
      put(
          "merge_queue",
          buildJsonObject {
            put("id", it.id)
            put("state", it.state.name)
            put("change_revision", it.changeRevisionId)
          },
      )
    }
    integration.mainRevision?.let { put("main", buildJsonObject { put("revision", it) }) }
  }

  private fun statusDeploymentJson(deployment: io.springkit.workflow.application.StatusDeployment) =
      buildJsonObject {
        put("candidate_id", deployment.candidateId)
        put("state", deployment.state.name)
        put("main_revision", deployment.mainRevision)
        put(
            "included_subtasks",
            kotlinx.serialization.json.buildJsonArray {
              deployment.includedSubTasks.forEach { add(JsonPrimitive(it)) }
            },
        )
        put("risk", deployment.risk.name)
        put("gate_required", deployment.gateRequired)
      }

  private fun statusReleaseJson(release: io.springkit.workflow.application.StatusRelease) =
      buildJsonObject {
        put("release_id", release.releaseId)
        put("state", release.state.name)
        put("feature_flag_id", release.featureFlagId)
        put("candidate_id", release.release.candidateId)
        put("gate_required", release.gateRequired)
      }

  private fun nextJson(action: NextAction) = buildJsonObject {
    put("actor", action.actor.name.lowercase())
    put("action", action.action)
    action.command?.let { put("command", it) }
  }

  private fun contentJson(content: io.springkit.workflow.application.ContentSnapshot) =
      buildJsonObject {
        put("revision", content.revision)
        put("fingerprint", content.fingerprint)
        put("dirty", content.dirty)
        put(
            "files",
            kotlinx.serialization.json.buildJsonArray {
              content.files.forEach { add(JsonPrimitive(it)) }
            },
        )
      }

  private fun gitJson(git: io.springkit.workflow.application.GitStatus) = buildJsonObject {
    put("revision", git.revision)
    put("fingerprint", git.fingerprint)
    put("dirty", git.dirty)
    put(
        "conflicts",
        kotlinx.serialization.json.buildJsonArray {
          git.conflicts.forEach { add(JsonPrimitive(it)) }
        },
    )
  }

  private fun String.toRisk(): Risk =
      when (lowercase()) {
        "normal" -> Risk.NORMAL
        "high" -> Risk.HIGH
        else -> throw InvalidWorkflowCommandArgument("--risk는 normal 또는 high여야 합니다.")
      }

  private fun String.toExposure(): Exposure =
      when (lowercase()) {
        "unchanged" -> Exposure.UNCHANGED
        "feature-flag" -> Exposure.FEATURE_FLAG
        else -> throw InvalidWorkflowCommandArgument("--exposure는 unchanged 또는 feature-flag여야 합니다.")
      }

  private fun String.toReviewLevel(): ReviewLevel =
      when (uppercase()) {
        "R" -> ReviewLevel.R
        "C" -> ReviewLevel.C
        "A" -> ReviewLevel.A
        else -> throw InvalidWorkflowCommandArgument("--level은 R, C 또는 A여야 합니다.")
      }

  private fun <T> WorkflowResult<T>.toJson(encoder: (T) -> JsonObject): WorkflowResult<JsonObject> =
      when (this) {
        is WorkflowResult.Success -> WorkflowResult.Success(encoder(data), next)
        is WorkflowResult.Failure -> this
      }

  private fun <T, R> WorkflowResult<T>.map(transform: (T) -> R): WorkflowResult<R> =
      when (this) {
        is WorkflowResult.Success -> WorkflowResult.Success(transform(data), next)
        is WorkflowResult.Failure -> this
      }

  private fun <T> WorkflowResult<WorkflowResult<T>>.flatten(): WorkflowResult<T> =
      when (this) {
        is WorkflowResult.Success -> data
        is WorkflowResult.Failure -> this
      }

  private fun <T, R> WorkflowResult<T>.flatMap(
      transform: (T) -> WorkflowResult<R>
  ): WorkflowResult<R> =
      when (this) {
        is WorkflowResult.Success -> transform(data)
        is WorkflowResult.Failure -> this
      }

  private fun <T, R> PortResult<T>.map(transform: (T) -> R): PortResult<R> =
      when (this) {
        is PortResult.Success -> PortResult.Success(transform(value))
        is PortResult.Failure -> this
      }

  private fun <T, R> PortResult<T>.flatMap(transform: (T) -> PortResult<R>): PortResult<R> =
      when (this) {
        is PortResult.Success -> transform(value)
        is PortResult.Failure -> this
      }

  private fun <T, R> PortResult<T>.flatMapResult(
      transform: (T) -> WorkflowResult<R>
  ): WorkflowResult<R> =
      when (this) {
        is PortResult.Success -> transform(value)
        is PortResult.Failure ->
            WorkflowResult.Failure(
                FailureData(
                    FailureCode.entries.firstOrNull { it.name == error.code }
                        ?: FailureCode.EXTERNAL_FAILURE,
                    error.message,
                    blockedBy = listOf(BlockedBy(error.code, error.message, error.target)),
                )
            )
      }

  private fun <T> PortResult<WorkflowResult<T>>.flatten(): WorkflowResult<T> =
      when (this) {
        is PortResult.Success -> value
        is PortResult.Failure ->
            WorkflowResult.Failure(
                FailureData(
                    FailureCode.entries.firstOrNull { it.name == error.code }
                        ?: FailureCode.EXTERNAL_FAILURE,
                    error.message,
                    blockedBy = listOf(BlockedBy(error.code, error.message, error.target)),
                )
            )
      }

  private fun <T> failure(code: FailureCode, message: String): WorkflowResult<T> =
      WorkflowResult.Failure(
          FailureData(code, message, blockedBy = listOf(BlockedBy(code.name, message)))
      )
}

private data class ReviewShowView(
    val pullRequest: io.springkit.workflow.domain.PullRequest,
    val status: io.springkit.workflow.application.StatusResponse,
)

private class InvalidWorkflowCommandArgument(message: String) : RuntimeException(message)

/** Stack 성공 결과를 명세의 JSON 계약으로 변환합니다. */
internal fun stackResponseJson(data: io.springkit.workflow.application.StackResponse): JsonObject =
    buildJsonObject {
      put(
          "subtask",
          WorkflowJson.format.encodeToJsonElement(
              io.springkit.workflow.domain.SubTask.serializer(),
              data.subTask,
          ),
      )
      put(
          "workspace",
          WorkflowJson.format.encodeToJsonElement(
              io.springkit.workflow.domain.Workspace.serializer(),
              data.workspace,
          ),
      )
      put("base_branch", data.baseBranch)
      put("base_revision", data.baseRevision)
      data.pullRequest?.let {
        put(
            "pull_request",
            WorkflowJson.format.encodeToJsonElement(
                io.springkit.workflow.domain.PullRequest.serializer(),
                it,
            ),
        )
      }
      put("diff_identity_same", data.diffIdentitySame)
      put("change_revision_retained", data.changeRevisionRetained)
      put("approval_retained", data.approvalRetained)
      put(
          "invalidated_state",
          kotlinx.serialization.json.buildJsonArray {
            data.invalidatedState.forEach { add(JsonPrimitive(it)) }
          },
      )
    }

/** Sync 성공 결과를 명세의 JSON 계약으로 변환합니다. */
internal fun syncResponseJson(data: io.springkit.workflow.application.SyncResponse): JsonObject =
    buildJsonObject {
      put(
          "subtask",
          WorkflowJson.format.encodeToJsonElement(
              io.springkit.workflow.domain.SubTask.serializer(),
              data.subTask,
          ),
      )
      put(
          "workspace",
          WorkflowJson.format.encodeToJsonElement(
              io.springkit.workflow.domain.Workspace.serializer(),
              data.workspace,
          ),
      )
      put("base_branch", data.baseBranch)
      put("base_revision", data.baseRevision)
      data.pullRequest?.let {
        put(
            "pull_request",
            WorkflowJson.format.encodeToJsonElement(
                io.springkit.workflow.domain.PullRequest.serializer(),
                it,
            ),
        )
      }
      put("diff_identity_same", data.diffIdentitySame)
      put("change_revision_retained", data.changeRevisionRetained)
      put("approval_retained", data.approvalRetained)
      put(
          "invalidated_state",
          kotlinx.serialization.json.buildJsonArray {
            data.invalidatedState.forEach { add(JsonPrimitive(it)) }
          },
      )
      data.recovery?.let { recovery ->
        put(
            "recovery",
            buildJsonObject {
              put("subtask", recovery.subTaskId)
              put(
                  "paths",
                  kotlinx.serialization.json.buildJsonArray {
                    recovery.paths.forEach { add(JsonPrimitive(it)) }
                  },
              )
              put("can_continue", recovery.canContinue)
              put("can_abort", recovery.canAbort)
            },
        )
      }
    }
