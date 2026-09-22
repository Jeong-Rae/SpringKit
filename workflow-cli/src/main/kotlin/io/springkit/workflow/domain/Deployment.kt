package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeploymentCandidateState {
  NOT_SELECTED,
  CANDIDATE,
  VALIDATING,
  AWAITING_DEPLOY_APPROVAL,
  CANARY,
  PRODUCTION,
  FAILED,
}

@Serializable
enum class ReleaseState {
  NOT_APPLICABLE,
  SAFE_DEFAULT,
  INTERNAL_VALIDATION,
  AWAITING_RELEASE_APPROVAL,
  ROLLOUT,
  RELEASED,
  CLEANUP_REQUIRED,
}

@Serializable
data class DeploymentCandidate(
    val id: CandidateId,
    val mainRevision: MainRevision,
    val includedSubTasks: List<SubTaskId>,
    val risks: Map<SubTaskId, Risk> = emptyMap(),
    val validations: List<Validation> = emptyList(),
    val state: DeploymentCandidateState = DeploymentCandidateState.CANDIDATE,
) {
  init {
    require(id.isNotBlank()) { "deployment candidate id must not be blank" }
    require(mainRevision.isNotBlank()) { "deployment candidate main revision must not be blank" }
    require(includedSubTasks.distinct().size == includedSubTasks.size) {
      "deployment candidate subtasks must be unique"
    }
    require(risks.keys.all { it in includedSubTasks }) {
      "candidate risk must reference included subtasks"
    }
    require(
        state != DeploymentCandidateState.PRODUCTION || requiredValidationsPassed(validations)
    ) {
      "production candidate must pass required validations"
    }
  }

  val risk: Risk
    get() = candidateRisk(risks.values)

  val deployGateRequired: Boolean
    get() = risk == Risk.HIGH && state == DeploymentCandidateState.AWAITING_DEPLOY_APPROVAL
}

@Serializable
data class Release(
    val id: ReleaseId,
    val candidateId: CandidateId,
    val featureFlagId: FeatureFlagId,
    val state: ReleaseState = ReleaseState.SAFE_DEFAULT,
    val productionReady: Boolean = false,
    val internalValidationPassed: Boolean = false,
    val cleanupSubTaskId: SubTaskId? = null,
) {
  init {
    require(id.isNotBlank()) { "release id must not be blank" }
    require(candidateId.isNotBlank()) { "release candidate id must not be blank" }
    require(featureFlagId.isNotBlank()) { "release feature flag id must not be blank" }
  }
}

fun candidateRisk(risks: Iterable<Risk>): Risk =
    if (risks.any { it == Risk.HIGH }) Risk.HIGH else Risk.NORMAL

fun candidateRiskForSubTasks(subTasks: Iterable<SubTask>): Risk =
    candidateRisk(subTasks.map { it.risk })

fun nextReleaseAction(release: Release): NextAction? =
    when (release.state) {
      ReleaseState.NOT_APPLICABLE,
      ReleaseState.RELEASED,
      -> null
      ReleaseState.SAFE_DEFAULT,
      ReleaseState.INTERNAL_VALIDATION,
      ->
          if (release.productionReady && release.internalValidationPassed) {
            NextAction(
                ActorKind.HUMAN,
                "approve_release",
                "./tools/workflow gate release ${release.id}",
            )
          } else {
            NextAction(ActorKind.WORKFLOW, "validate_release", null)
          }
      ReleaseState.AWAITING_RELEASE_APPROVAL ->
          NextAction(
              ActorKind.HUMAN,
              "approve_release",
              "./tools/workflow gate release ${release.id}",
          )
      ReleaseState.ROLLOUT -> NextAction(ActorKind.WORKFLOW, "continue_rollout", null)
      ReleaseState.CLEANUP_REQUIRED ->
          NextAction(ActorKind.WORKFLOW, "create_cleanup_subtask", null)
    }
