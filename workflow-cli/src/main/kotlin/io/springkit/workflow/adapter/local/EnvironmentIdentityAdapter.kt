package io.springkit.workflow.adapter.local

import io.springkit.workflow.application.AuthorizeRequest
import io.springkit.workflow.application.AuthorizeResponse
import io.springkit.workflow.application.Capability
import io.springkit.workflow.application.CurrentActorRequest
import io.springkit.workflow.application.CurrentActorResponse
import io.springkit.workflow.application.IdentityPort
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import java.nio.file.Path

/**
 * 환경 주체와 인증된 `gh api user`를 사용해 현재 principal과 사람 Gate를 판별합니다.
 *
 * `gh` 인증 성공만으로는 HUMAN을 부여하지 않으며 [humanActorIds]에 명시된 계정만 사람으로 분류합니다.
 */
class EnvironmentIdentityAdapter(
    private val commandRunner: CommandRunner = LocalCommandRunner(),
    private val workingDirectory: Path = Path.of(System.getProperty("user.dir")),
    private val environment: Map<String, String> = System.getenv(),
    private val humanActorIds: Set<String> = emptySet(),
) : IdentityPort {
  override fun currentActor(request: CurrentActorRequest): PortResult<CurrentActorResponse> {
    val environmentActor = environmentActor()
    if (isAutomation()) {
      return environmentActor?.let { PortResult.Success(CurrentActorResponse(it)) }
          ?: failure("IDENTITY_UNAVAILABLE", "자동화 실행의 principal을 확인할 수 없습니다.")
    }

    val ghActor = authenticatedGithubActor()
    if (ghActor != null) return PortResult.Success(CurrentActorResponse(ghActor))
    if (environmentActor != null) return PortResult.Success(CurrentActorResponse(environmentActor))
    return failure("IDENTITY_UNAVAILABLE", "현재 principal을 확인할 수 없습니다.")
  }

  override fun authorize(request: AuthorizeRequest): PortResult<AuthorizeResponse> {
    if (request.target.isBlank()) {
      return failure("AUTHORIZATION_TARGET_INVALID", "권한 확인 대상이 비어 있습니다.")
    }
    val current =
        when (val result = currentActor()) {
          is PortResult.Failure -> return result
          is PortResult.Success -> result.value.actor
        }
    if (current.id != request.actor.id) {
      return PortResult.Success(
          AuthorizeResponse(
              allowed = false,
              reason = "요청 주체가 현재 인증된 principal과 다릅니다.",
          )
      )
    }
    val humanGate =
        request.capability in
            setOf(Capability.READY, Capability.APPROVE, Capability.DEPLOY, Capability.RELEASE)
    if (humanGate && !current.isHuman) {
      return PortResult.Success(
          AuthorizeResponse(
              allowed = false,
              reason = "사람 Gate는 인증된 HUMAN principal만 수행할 수 있습니다.",
          )
      )
    }
    return PortResult.Success(AuthorizeResponse(allowed = true))
  }

  private fun authenticatedGithubActor(): Actor? {
    val result =
        try {
          commandRunner.run(listOf("gh", "api", "user", "--jq", ".login"), workingDirectory)
        } catch (_: Exception) {
          return null
        }
    if (result.exitCode != 0) return null
    val login = result.stdout.trim()
    val kind = if (itIsConfiguredHuman(login)) ActorKind.HUMAN else ActorKind.AGENT
    return login.takeIf(String::isNotBlank)?.let { Actor(it, kind, it) }
  }

  private fun environmentActor(): Actor? {
    val id =
        environment["GH_USER"]?.takeIf(String::isNotBlank)
            ?: environment["GITHUB_ACTOR"]?.takeIf(String::isNotBlank)
            ?: environment["USER"]?.takeIf(String::isNotBlank)
            ?: environment["USERNAME"]?.takeIf(String::isNotBlank)
    val kind =
        when {
          id in humanActorIds -> ActorKind.HUMAN
          environment["WORKFLOW_ACTOR_KIND"]?.uppercase() == "WORKFLOW" -> ActorKind.WORKFLOW
          environment["WORKFLOW_ACTOR_KIND"]?.uppercase() == "AGENT" -> ActorKind.AGENT
          else -> if (isAutomation()) ActorKind.WORKFLOW else ActorKind.AGENT
        }
    return id?.let { Actor(it, kind, it) }
  }

  /** 명시적으로 허용된 계정만 사람 주체로 분류합니다. */
  private fun itIsConfiguredHuman(login: String): Boolean = login in humanActorIds

  private fun isAutomation(): Boolean =
      environment["GITHUB_ACTIONS"]?.equals("true", ignoreCase = true) == true ||
          environment["CI"]?.equals("true", ignoreCase = true) == true ||
          environment["WORKFLOW_ACTOR_KIND"]?.uppercase() in setOf("AGENT", "WORKFLOW")

  private fun <T> failure(code: String, message: String): PortResult<T> =
      PortResult.Failure(PortError(code, message))
}

/** 로컬 환경 주체 adapter의 짧은 이름입니다. */
typealias LocalIdentityAdapter = EnvironmentIdentityAdapter
