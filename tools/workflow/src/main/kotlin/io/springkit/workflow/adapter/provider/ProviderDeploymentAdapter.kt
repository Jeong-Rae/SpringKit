package io.springkit.workflow.adapter.provider

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.ChangeStatus
import io.springkit.workflow.application.CreateCandidateRequest
import io.springkit.workflow.application.CreateCandidateResponse
import io.springkit.workflow.application.DeploymentPort
import io.springkit.workflow.application.GetCandidateRequest
import io.springkit.workflow.application.GetCandidateResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PromoteProductionRequest
import io.springkit.workflow.application.PromoteProductionResponse
import io.springkit.workflow.application.StartCanaryRequest
import io.springkit.workflow.application.StartCanaryResponse
import io.springkit.workflow.application.ValidateCandidateRequest
import io.springkit.workflow.application.ValidateCandidateResponse
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.CandidateId
import io.springkit.workflow.domain.DeploymentCandidate
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.MainRevision
import io.springkit.workflow.domain.Risk
import io.springkit.workflow.domain.SubTaskId
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * 설치된 배포 provider CLI에 배포 명령을 위임하는 [DeploymentPort] 구현입니다.
 */
class ProviderDeploymentAdapter(
    commandPrefix: List<String>,
    private val workingDirectory: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
) : DeploymentPort {
  private val commandPrefix: List<String> = commandPrefix.toList()

  override fun createCandidate(
      request: CreateCandidateRequest
  ): PortResult<CreateCandidateResponse> {
    validateCreateRequest(request)?.let {
      return it
    }
    val requestDto =
        ProviderCreateCandidateRequest(
            mainRevision = request.mainRevision,
            includedSubTasks = request.includedSubTasks,
            risks = request.risks.mapValues { (_, risk) -> risk.name },
        )
    return invoke("create-candidate", request.mainRevision, requestDto).flatMap { payload ->
      PortResult.Success(
          CreateCandidateResponse(
              candidate = payload.candidate,
              change = payload.change ?: receipt("create-candidate", payload.candidate.id),
          )
      )
    }
  }

  override fun getCandidate(request: GetCandidateRequest): PortResult<GetCandidateResponse> {
    return invoke(
            "get-candidate",
            request.candidateId,
            ProviderCandidateRequest(request.candidateId),
            expectedCandidateId = request.candidateId,
        )
        .flatMap { payload -> PortResult.Success(GetCandidateResponse(payload.candidate)) }
  }

  override fun validate(request: ValidateCandidateRequest): PortResult<ValidateCandidateResponse> {
    validateCandidateRequest(request.candidateId, request.mainRevision)?.let {
      return it
    }
    return invoke(
            "validate-candidate",
            request.candidateId,
            ProviderValidateCandidateRequest(request.candidateId, request.mainRevision),
            expectedCandidateId = request.candidateId,
        )
        .flatMap { payload ->
          PortResult.Success(
              ValidateCandidateResponse(
                  candidate = payload.candidate,
                  change = payload.change ?: receipt("validate-candidate", request.candidateId),
              )
          )
        }
  }

  override fun startCanary(request: StartCanaryRequest): PortResult<StartCanaryResponse> {
    validateCandidateId(request.candidateId)?.let {
      return it
    }
    return invoke(
            "start-canary",
            request.candidateId,
            ProviderStartCanaryRequest(request.candidateId, request.actor?.toDto()),
            expectedCandidateId = request.candidateId,
        )
        .flatMap { payload ->
          PortResult.Success(
              StartCanaryResponse(
                  candidate = payload.candidate,
                  change = payload.change ?: receipt("start-canary", request.candidateId),
              )
          )
        }
  }

  override fun promoteProduction(
      request: PromoteProductionRequest
  ): PortResult<PromoteProductionResponse> {
    validateCandidateId(request.candidateId)?.let {
      return it
    }
    return invoke(
            "promote-production",
            request.candidateId,
            ProviderPromoteProductionRequest(request.candidateId, request.expectedState.name),
            expectedCandidateId = request.candidateId,
        )
        .flatMap { payload ->
          PortResult.Success(
              PromoteProductionResponse(
                  candidate = payload.candidate,
                  change = payload.change ?: receipt("promote-production", request.candidateId),
              )
          )
        }
  }

  private fun <T> invoke(
      operation: String,
      target: String,
      request: T,
      expectedCandidateId: CandidateId? = null,
  ): PortResult<ProviderResponse> where T : Any {
    if (target.isBlank()) {
      return failure(
          "DEPLOYMENT_PROVIDER_REQUEST_INVALID",
          "배포 대상 식별자가 비어 있습니다.",
          target,
      )
    }
    if (commandPrefix.isEmpty() || commandPrefix.any { it.isBlank() }) {
      return failure(
          "DEPLOYMENT_PROVIDER_COMMAND_INVALID",
          "배포 provider 명령 접두사가 비어 있습니다.",
          target,
      )
    }
    val requestJson =
        try {
          encodeRequest(request)
        } catch (failure: SerializationException) {
          return failure(
              "DEPLOYMENT_PROVIDER_REQUEST_INVALID",
              "배포 provider 요청 JSON을 만들 수 없습니다.",
              target,
          )
        } catch (failure: IllegalArgumentException) {
          return failure(
              "DEPLOYMENT_PROVIDER_REQUEST_INVALID",
              "배포 provider 요청이 올바르지 않습니다.",
              target,
          )
        }
    val command = commandPrefix + operation + listOf("--request-json", requestJson)
    val result =
        try {
          commandRunner.run(command, workingDirectory)
        } catch (failure: Exception) {
          return failure(
              "DEPLOYMENT_PROVIDER_COMMAND_FAILED",
              "배포 provider 명령을 실행할 수 없습니다: ${failure.message ?: failure::class.simpleName}",
              target,
              retryable = true,
          )
        }
    if (result.exitCode != 0) {
      return failure(
          "DEPLOYMENT_PROVIDER_COMMAND_FAILED",
          result.stderr
              .trim()
              .ifBlank { result.stdout.trim() }
              .ifBlank {
                "배포 provider 명령이 종료 코드 ${result.exitCode}로 실패했습니다."
              },
          target,
          retryable = result.exitCode == 2,
      )
    }
    return decodeResponse(result.stdout, target, expectedCandidateId)
  }

  private fun encodeRequest(request: Any): String =
      when (request) {
        is ProviderCreateCandidateRequest -> json.encodeToString(request)
        is ProviderCandidateRequest -> json.encodeToString(request)
        is ProviderValidateCandidateRequest -> json.encodeToString(request)
        is ProviderStartCanaryRequest -> json.encodeToString(request)
        is ProviderPromoteProductionRequest -> json.encodeToString(request)
        else -> error("지원하지 않는 배포 provider 요청입니다.")
      }

  private fun decodeResponse(
      stdout: String,
      target: String,
      expectedCandidateId: CandidateId?,
  ): PortResult<ProviderResponse> {
    val payload =
        try {
          json.decodeFromString<ProviderResponseDto>(stdout)
        } catch (failure: SerializationException) {
          return failure(
              "DEPLOYMENT_PROVIDER_RESPONSE_INVALID",
              "배포 provider 응답 JSON을 해석할 수 없습니다.",
              target,
          )
        } catch (failure: IllegalArgumentException) {
          return failure(
              "DEPLOYMENT_PROVIDER_RESPONSE_INVALID",
              "배포 provider 응답 JSON이 올바르지 않습니다.",
              target,
          )
        }
    val candidateDto =
        payload.candidate
            ?: return failure(
                "DEPLOYMENT_PROVIDER_RESPONSE_INVALID",
                "배포 provider 응답에 candidate가 없습니다.",
                target,
            )
    val candidate =
        try {
          candidateDto.toDomain()
        } catch (failure: IllegalArgumentException) {
          return failure(
              "DEPLOYMENT_PROVIDER_INVARIANT_VIOLATION",
              "배포 provider candidate가 Workflow 불변식을 만족하지 않습니다.",
              target,
          )
        }
    if (expectedCandidateId != null && candidate.id != expectedCandidateId) {
      return failure(
          "DEPLOYMENT_PROVIDER_INVARIANT_VIOLATION",
          "배포 provider가 요청한 후보와 다른 candidate를 반환했습니다.",
          target,
      )
    }
    val change =
        try {
          payload.change?.toDomain()
        } catch (failure: IllegalArgumentException) {
          return failure(
              "DEPLOYMENT_PROVIDER_INVARIANT_VIOLATION",
              "배포 provider change가 Workflow 불변식을 만족하지 않습니다.",
              target,
          )
        }
    return PortResult.Success(ProviderResponse(candidate, change))
  }

  private fun validateCreateRequest(request: CreateCandidateRequest): PortResult.Failure? {
    if (request.mainRevision.isBlank()) {
      return invalidArgument("배포 후보 main revision이 비어 있습니다.", request.mainRevision)
    }
    if (request.includedSubTasks.any { it.isBlank() }) {
      return invalidArgument("배포 후보 SubTask 식별자가 비어 있습니다.", request.mainRevision)
    }
    if (request.risks.keys.any { it.isBlank() }) {
      return invalidArgument("배포 후보 위험도 대상이 비어 있습니다.", request.mainRevision)
    }
    return null
  }

  private fun validateCandidateRequest(
      candidateId: CandidateId,
      mainRevision: MainRevision,
  ): PortResult.Failure? {
    validateCandidateId(candidateId)?.let {
      return it
    }
    if (mainRevision.isBlank()) {
      return invalidArgument("배포 후보 main revision이 비어 있습니다.", candidateId)
    }
    return null
  }

  private fun validateCandidateId(candidateId: CandidateId): PortResult.Failure? =
      if (candidateId.isBlank()) {
        invalidArgument("배포 후보 식별자가 비어 있습니다.", candidateId)
      } else {
        null
      }

  private fun invalidArgument(message: String, target: String): PortResult.Failure =
      failure("DEPLOYMENT_PROVIDER_REQUEST_INVALID", message, target)

  private fun receipt(operation: String, target: String): ChangeReceipt =
      ChangeReceipt(
          id = "provider-deployment-$operation-$target",
          operation = "provider-deployment-$operation",
      )

  private fun failure(
      code: String,
      message: String,
      target: String,
      retryable: Boolean = false,
  ): PortResult.Failure = PortResult.Failure(PortError(code, message, retryable, target))

  private fun <T, R> PortResult<T>.flatMap(transform: (T) -> PortResult<R>): PortResult<R> =
      when (this) {
        is PortResult.Success -> transform(value)
        is PortResult.Failure -> this
      }

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      encodeDefaults = false
    }
  }
}

@Serializable
private data class ProviderCreateCandidateRequest(
    @SerialName("main_revision") val mainRevision: MainRevision,
    @SerialName("included_subtasks") val includedSubTasks: List<SubTaskId>,
    val risks: Map<SubTaskId, String>,
)

@Serializable
private data class ProviderCandidateRequest(
    @SerialName("candidate_id") val candidateId: CandidateId
)

@Serializable
private data class ProviderValidateCandidateRequest(
    @SerialName("candidate_id") val candidateId: CandidateId,
    @SerialName("main_revision") val mainRevision: MainRevision,
)

@Serializable
private data class ProviderStartCanaryRequest(
    @SerialName("candidate_id") val candidateId: CandidateId,
    val actor: ProviderActor? = null,
)

@Serializable
private data class ProviderPromoteProductionRequest(
    @SerialName("candidate_id") val candidateId: CandidateId,
    @SerialName("expected_state") val expectedState: String,
)

@Serializable
private data class ProviderActor(
    val id: String,
    val kind: String,
    val name: String? = null,
)

@Serializable
private data class ProviderResponseDto(
    val candidate: ProviderCandidateDto? = null,
    val change: ProviderChangeDto? = null,
)

@Serializable
private data class ProviderCandidateDto(
    val id: String,
    @SerialName("main_revision") val mainRevision: String,
    @SerialName("included_subtasks") val includedSubTasks: List<String>,
    val risks: Map<String, Risk> = emptyMap(),
    val validations: List<ProviderValidationDto> = emptyList(),
    val state: DeploymentCandidateState,
) {
  fun toDomain(): DeploymentCandidate {
    require(id.isNotBlank())
    require(mainRevision.isNotBlank())
    require(includedSubTasks.all { it.isNotBlank() })
    val candidateRisks = risks.map { (subTaskId, risk) ->
      require(subTaskId.isNotBlank())
      subTaskId to risk
    }
    return DeploymentCandidate(
        id = id,
        mainRevision = mainRevision,
        includedSubTasks = includedSubTasks,
        risks = candidateRisks.toMap(),
        validations = validations.map { it.toDomain() },
        state = state,
    )
  }
}

@Serializable
private data class ProviderValidationDto(
    val id: String,
    val name: String,
    val status: ValidationStatus,
    val required: Boolean = true,
    val revision: String? = null,
    val message: String? = null,
) {
  fun toDomain(): Validation =
      Validation(
          id = id.also { require(it.isNotBlank()) },
          name = name.also { require(it.isNotBlank()) },
          status = status,
          required = required,
          revision = revision,
          message = message,
      )
}

@Serializable
private data class ProviderChangeDto(
    val id: String,
    val operation: String,
    val status: ChangeStatus,
    @SerialName("before_revision") val beforeRevision: String? = null,
    @SerialName("after_revision") val afterRevision: String? = null,
) {
  init {
    require(beforeRevision == null || beforeRevision.isNotBlank())
    require(afterRevision == null || afterRevision.isNotBlank())
  }

  fun toDomain(): ChangeReceipt =
      ChangeReceipt(
          id = id,
          operation = operation,
          status = status,
          beforeRevision = beforeRevision,
          afterRevision = afterRevision,
      )
}

private data class ProviderResponse(
    val candidate: DeploymentCandidate,
    val change: ChangeReceipt?,
)

private fun Actor.toDto(): ProviderActor = ProviderActor(id = id, kind = kind.name, name = name)
