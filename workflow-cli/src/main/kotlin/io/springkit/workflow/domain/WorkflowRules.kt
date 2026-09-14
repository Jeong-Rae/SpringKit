package io.springkit.workflow.domain

/** Application 유스케이스에서 사용하는 순수 정책 함수입니다. */
object WorkflowRules {
  fun hasOpenRequiredThread(threads: Iterable<ReviewThread>): Boolean = threads.any {
    it.isOpen && it.level == ReviewLevel.R
  }

  fun openRequiredThreads(threads: Iterable<ReviewThread>): List<ReviewThread> = threads.filter {
    it.isOpen && it.level == ReviewLevel.R
  }

  fun approvalApplies(approval: Approval?, changeRevision: ChangeRevision): Boolean =
      approval != null &&
          approval.active &&
          approval.actor.isHuman &&
          approval.changeRevisionId == changeRevision.id &&
          approval.diffIdentity == changeRevision.diff.identity

  fun approvalApplies(
      approval: Approval?,
      changeRevisionId: ChangeRevisionId,
      diffIdentity: DiffIdentity,
  ): Boolean =
      approval != null &&
          approval.active &&
          approval.actor.isHuman &&
          approval.changeRevisionId == changeRevisionId &&
          approval.diffIdentity == diffIdentity

  fun dependencyCycle(dependencies: Iterable<Dependency>): List<SubTaskId> {
    val graph = dependencies.groupBy({ it.subTaskId }, { it.requires })
    val visiting = mutableSetOf<SubTaskId>()
    val visited = mutableSetOf<SubTaskId>()
    val path = mutableListOf<SubTaskId>()

    fun visit(node: SubTaskId): List<SubTaskId>? {
      if (node in visiting) {
        val index = path.indexOf(node)
        return (if (index >= 0) path.subList(index, path.size) else path).toList() + node
      }
      if (!visited.add(node)) return null
      visiting += node
      path += node
      for (next in graph[node].orEmpty()) {
        val cycle = visit(next)
        if (cycle != null) return cycle
      }
      path.removeAt(path.lastIndex)
      visiting -= node
      return null
    }

    return graph.keys.firstNotNullOfOrNull(::visit).orEmpty()
  }

  fun hasDependencyCycle(dependencies: Iterable<Dependency>): Boolean =
      dependencyCycle(dependencies).isNotEmpty()

  fun canEnterMergeQueue(
      condition: MergeQueueCondition,
  ): Boolean = condition.canQueue

  fun canEnterMergeQueue(
      pullRequest: PullRequest,
      requiredValidations: Iterable<Validation>,
      dependencyIntegrated: Boolean,
  ): Boolean {
    val condition =
        MergeQueueCondition(
            ready = pullRequest.state in setOf(PullRequestState.READY, PullRequestState.REVIEW),
            approvalApplicable = approvalApplies(pullRequest.approval, pullRequest.changeRevision),
            requiredCiPassed = pullRequest.ci == CiStatus.PASSED,
            requiredValidationsPassed = requiredValidationsPassed(requiredValidations),
            openRequiredThreads = hasOpenRequiredThread(pullRequest.reviewRevision.threads),
            dependencyIntegrated = dependencyIntegrated,
        )
    return canEnterMergeQueue(condition)
  }

  fun humanGateCondition(
      gate: GateType,
      actor: Actor,
      pullRequest: PullRequest? = null,
      candidate: DeploymentCandidate? = null,
      release: Release? = null,
  ): FailureData? {
    if (!actor.isHuman) {
      return FailureData(
          code = FailureCode.HUMAN_REQUIRED,
          message = "${gate.name.lowercase()} gate requires a human actor",
          blockedBy = listOf(BlockedBy("HUMAN_REQUIRED", "a human decision is required")),
      )
    }
    return when (gate) {
      GateType.READY ->
          if (pullRequest == null || pullRequest.state != PullRequestState.DRAFT) {
            FailureData(FailureCode.STATE_CONFLICT, "ready gate requires a draft pull request")
          } else null
      GateType.APPROVE ->
          if (
              pullRequest == null ||
                  pullRequest.state !in setOf(PullRequestState.READY, PullRequestState.REVIEW)
          ) {
            FailureData(
                FailureCode.STATE_CONFLICT,
                "approve gate requires a reviewable pull request",
            )
          } else null
      GateType.DEPLOY ->
          if (
              candidate == null ||
                  candidate.risk != Risk.HIGH ||
                  candidate.state != DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL
          ) {
            FailureData(
                FailureCode.STATE_CONFLICT,
                "deploy gate requires a high-risk candidate awaiting approval",
            )
          } else null
      GateType.RELEASE ->
          if (
              release == null ||
                  !release.productionReady ||
                  !release.internalValidationPassed ||
                  release.state != ReleaseState.AWAITING_RELEASE_APPROVAL
          ) {
            FailureData(
                FailureCode.STATE_CONFLICT,
                "release gate requires production and internal validation",
            )
          } else null
    }
  }

  fun nextReleaseAction(release: Release): NextAction? =
      io.springkit.workflow.domain.nextReleaseAction(release)
}

fun hasOpenRequiredThread(threads: Iterable<ReviewThread>): Boolean =
    WorkflowRules.hasOpenRequiredThread(threads)

fun approvalApplies(approval: Approval?, changeRevision: ChangeRevision): Boolean =
    WorkflowRules.approvalApplies(approval, changeRevision)

fun dependencyCycle(dependencies: Iterable<Dependency>): List<SubTaskId> =
    WorkflowRules.dependencyCycle(dependencies)

fun hasDependencyCycle(dependencies: Iterable<Dependency>): Boolean =
    WorkflowRules.hasDependencyCycle(dependencies)

fun canEnterMergeQueue(condition: MergeQueueCondition): Boolean =
    WorkflowRules.canEnterMergeQueue(condition)

fun humanGateCondition(
    gate: GateType,
    actor: Actor,
    pullRequest: PullRequest? = null,
    candidate: DeploymentCandidate? = null,
    release: Release? = null,
): FailureData? = WorkflowRules.humanGateCondition(gate, actor, pullRequest, candidate, release)
