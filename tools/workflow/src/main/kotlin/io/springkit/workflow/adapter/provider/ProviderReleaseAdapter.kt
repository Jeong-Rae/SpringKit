package io.springkit.workflow.adapter.provider

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.ChangeStatus
import io.springkit.workflow.application.ContinueReleaseRequest
import io.springkit.workflow.application.ContinueReleaseResponse
import io.springkit.workflow.application.CreateReleaseRequest
import io.springkit.workflow.application.CreateReleaseResponse
import io.springkit.workflow.application.GetReleaseRequest
import io.springkit.workflow.application.GetReleaseResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ReleasePort
import io.springkit.workflow.application.StartReleaseRequest
import io.springkit.workflow.application.StartReleaseResponse
import io.springkit.workflow.application.ValidateReleaseRequest
import io.springkit.workflow.application.ValidateReleaseResponse
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.Release
import io.springkit.workflow.domain.ReleaseState
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * 설치된 외부 공개 provider CLI에 Release 작업을 위임하는 어댑터입니다.
 */
class ProviderReleaseAdapter(
    commandPrefix: List<String>,
    private val workingDirectory: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
) : ReleasePort {
  private val commandPrefix: List<String> = commandPrefix.toList()

  override fun get(request: GetReleaseRequest): PortResult<GetReleaseResponse> {
    val target = request.releaseId ?: request.candidateId ?: "all"
    validateGetRequest(request)?.let {
      return it
    }
    return execute(
        operation = Operation.GET,
        requestJson = json.encodeToString(GetRequestPayload.serializer(), request.toPayload()),
        target = target,
    ) { output ->
      val response = json.decodeFromString<GetResponsePayload>(output)
      GetReleaseResponse(response.releases.map(ReleasePayload::toDomain))
    }
  }

  override fun create(request: CreateReleaseRequest): PortResult<CreateReleaseResponse> {
    validateCreateRequest(request)?.let {
      return it
    }
    return executeChange(
        operation = Operation.CREATE,
        requestJson = json.encodeToString(CreateRequestPayload.serializer(), request.toPayload()),
        target = request.releaseId,
    ) { output, change ->
      val response = json.decodeFromString<ChangeResponsePayload>(output)
      val release = response.release.toDomain()
      require(release.id == request.releaseId) {
        "provider returned release ${release.id} for ${request.releaseId}"
      }
      require(release.candidateId == request.candidateId) {
        "provider returned candidate ${release.candidateId} for ${request.candidateId}"
      }
      require(release.featureFlagId == request.featureFlagId) {
        "provider returned feature flag ${release.featureFlagId} for ${request.featureFlagId}"
      }
      CreateReleaseResponse(release, change ?: receipt(Operation.CREATE, request.releaseId))
    }
  }

  override fun validateInternal(
      request: ValidateReleaseRequest
  ): PortResult<ValidateReleaseResponse> {
    validateReleaseId(request.releaseId)?.let {
      return it
    }
    return executeChange(
        operation = Operation.VALIDATE,
        requestJson =
            json.encodeToString(
                ValidateRequestPayload.serializer(),
                request.toPayload(),
            ),
        target = request.releaseId,
    ) { output, change ->
      val response = json.decodeFromString<ChangeResponsePayload>(output)
      val release = response.release.toDomain()
      require(release.id == request.releaseId) {
        "provider returned release ${release.id} for ${request.releaseId}"
      }
      ValidateReleaseResponse(
          release,
          change ?: receipt(Operation.VALIDATE, request.releaseId),
      )
    }
  }

  override fun start(request: StartReleaseRequest): PortResult<StartReleaseResponse> {
    validateReleaseId(request.releaseId)?.let {
      return it
    }
    return executeChange(
        operation = Operation.START,
        requestJson = json.encodeToString(StartRequestPayload.serializer(), request.toPayload()),
        target = request.releaseId,
    ) { output, change ->
      val response = json.decodeFromString<ChangeResponsePayload>(output)
      val release = response.release.toDomain()
      require(release.id == request.releaseId) {
        "provider returned release ${release.id} for ${request.releaseId}"
      }
      StartReleaseResponse(release, change ?: receipt(Operation.START, request.releaseId))
    }
  }

  override fun continueRollout(
      request: ContinueReleaseRequest
  ): PortResult<ContinueReleaseResponse> {
    validateReleaseId(request.releaseId)?.let {
      return it
    }
    return executeChange(
        operation = Operation.CONTINUE,
        requestJson = json.encodeToString(ContinueRequestPayload.serializer(), request.toPayload()),
        target = request.releaseId,
    ) { output, change ->
      val response = json.decodeFromString<ChangeResponsePayload>(output)
      val release = response.release.toDomain()
      require(release.id == request.releaseId) {
        "provider returned release ${release.id} for ${request.releaseId}"
      }
      ContinueReleaseResponse(
          release,
          change ?: receipt(Operation.CONTINUE, request.releaseId),
      )
    }
  }

  private fun <T> execute(
      operation: Operation,
      requestJson: String,
      target: String,
      decode: (String) -> T,
  ): PortResult<T> {
    val result =
        when (val command = run(operation, requestJson, target)) {
          is PortResult.Failure -> return command
          is PortResult.Success -> command.value
        }
    return try {
      PortResult.Success(decode(result.stdout))
    } catch (failure: SerializationException) {
      invalidResponse(operation, target)
    } catch (failure: IllegalArgumentException) {
      invariantFailure(operation, target, failure)
    } catch (failure: IllegalStateException) {
      invariantFailure(operation, target, failure)
    }
  }

  private fun <T> executeChange(
      operation: Operation,
      requestJson: String,
      target: String,
      decode: (String, ChangeReceipt?) -> T,
  ): PortResult<T> {
    val result =
        when (val command = run(operation, requestJson, target)) {
          is PortResult.Failure -> return command
          is PortResult.Success -> command.value
        }
    return try {
      val envelope = json.decodeFromString<ChangeResponsePayload>(result.stdout)
      val change = envelope.change?.toDomain()
      PortResult.Success(decode(result.stdout, change))
    } catch (failure: SerializationException) {
      invalidResponse(operation, target)
    } catch (failure: IllegalArgumentException) {
      invariantFailure(operation, target, failure)
    } catch (failure: IllegalStateException) {
      invariantFailure(operation, target, failure)
    }
  }

  private fun run(
      operation: Operation,
      requestJson: String,
      target: String,
  ): PortResult<CommandResult> {
    if (commandPrefix.isEmpty() || commandPrefix.any { it.isBlank() }) {
      return PortResult.Failure(
          PortError(
              code = "RELEASE_PROVIDER_COMMAND_INVALID",
              message = "외부 공개 provider CLI 명령 접두사가 비어 있거나 공백입니다.",
              target = target,
          )
      )
    }
    val command = commandPrefix + operation.value + "--request-json" + requestJson
    return try {
      val result = commandRunner.run(command, workingDirectory)
      if (result.exitCode == 0) {
        PortResult.Success(result)
      } else {
        PortResult.Failure(
            PortError(
                code = "RELEASE_PROVIDER_COMMAND_FAILED",
                message =
                    result.stderr
                        .trim()
                        .ifBlank { result.stdout.trim() }
                        .ifBlank {
                          "외부 공개 provider 명령이 종료 코드 ${result.exitCode}로 실패했습니다."
                        },
                retryable = result.exitCode == 2,
                target = target,
            )
        )
      }
    } catch (failure: Exception) {
      PortResult.Failure(
          PortError(
              code = "RELEASE_PROVIDER_COMMAND_FAILED",
              message =
                  "외부 공개 provider 명령을 실행할 수 없습니다: " +
                      (failure.message ?: failure::class.simpleName),
              retryable = true,
              target = target,
          )
      )
    }
  }

  private fun invalidResponse(
      operation: Operation,
      target: String,
  ): PortResult.Failure =
      PortResult.Failure(
          PortError(
              code = "RELEASE_PROVIDER_RESPONSE_INVALID",
              message = "외부 공개 provider ${operation.value} 응답 JSON을 해석할 수 없습니다.",
              target = target,
          )
      )

  private fun invariantFailure(
      operation: Operation,
      target: String,
      failure: Exception,
  ): PortResult.Failure =
      PortResult.Failure(
          PortError(
              code = "RELEASE_PROVIDER_INVARIANT_VIOLATION",
              message =
                  "외부 공개 provider ${operation.value} 응답이 Workflow Release 불변식을 위반했습니다: " +
                      (failure.message ?: "unknown invariant"),
              target = target,
          )
      )

  private fun receipt(operation: Operation, target: String): ChangeReceipt =
      ChangeReceipt(
          id = "provider-release-${operation.value}-$target",
          operation = "provider-release-${operation.value}",
      )

  private fun validateGetRequest(request: GetReleaseRequest): PortResult.Failure? {
    if (request.releaseId?.isBlank() == true) {
      return invalidRequest(request.releaseId)
    }
    if (request.candidateId?.isBlank() == true) {
      return invalidRequest(request.candidateId)
    }
    return null
  }

  private fun validateCreateRequest(request: CreateReleaseRequest): PortResult.Failure? {
    if (request.releaseId.isBlank()) return invalidRequest(request.releaseId)
    if (request.candidateId.isBlank()) return invalidRequest(request.candidateId)
    if (request.featureFlagId.isBlank()) return invalidRequest(request.featureFlagId)
    return null
  }

  private fun validateReleaseId(releaseId: String): PortResult.Failure? =
      if (releaseId.isBlank()) invalidRequest(releaseId) else null

  private fun invalidRequest(target: String?): PortResult.Failure =
      PortResult.Failure(
          PortError(
              code = "RELEASE_PROVIDER_REQUEST_INVALID",
              message = "외부 공개 provider 요청 식별자가 비어 있습니다.",
              target = target,
          )
      )

  private enum class Operation(val value: String) {
    GET("get-release"),
    CREATE("create-release"),
    VALIDATE("validate-release"),
    START("start-release"),
    CONTINUE("continue-rollout"),
  }

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}

@Serializable
private data class GetRequestPayload(
    @SerialName("release_id") val releaseId: String? = null,
    @SerialName("candidate_id") val candidateId: String? = null,
)

@Serializable
private data class CreateRequestPayload(
    @SerialName("release_id") val releaseId: String,
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("feature_flag_id") val featureFlagId: String,
)

@Serializable
private data class ValidateRequestPayload(
    @SerialName("release_id") val releaseId: String,
    val actor: ActorPayload? = null,
)

@Serializable
private data class StartRequestPayload(
    @SerialName("release_id") val releaseId: String,
    val actor: ActorPayload,
)

@Serializable
private data class ContinueRequestPayload(@SerialName("release_id") val releaseId: String)

@Serializable
private data class ActorPayload(
    val id: String,
    val kind: String,
    val name: String? = null,
)

@Serializable private data class GetResponsePayload(val releases: List<ReleasePayload>)

@Serializable
private data class ChangeResponsePayload(
    val release: ReleasePayload,
    val change: ChangePayload? = null,
)

@Serializable
private data class ReleasePayload(
    @SerialName("release_id") val id: String,
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("feature_flag_id") val featureFlagId: String,
    val state: ReleaseState = ReleaseState.SAFE_DEFAULT,
    @SerialName("production_ready") val productionReady: Boolean = false,
    @SerialName("internal_validation_passed") val internalValidationPassed: Boolean = false,
    @SerialName("cleanup_sub_task_id") val cleanupSubTaskId: String? = null,
) {
  fun toDomain(): Release =
      Release(
          id = id,
          candidateId = candidateId,
          featureFlagId = featureFlagId,
          state = state,
          productionReady = productionReady,
          internalValidationPassed = internalValidationPassed,
          cleanupSubTaskId = cleanupSubTaskId,
      )
}

@Serializable
private data class ChangePayload(
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

private fun GetReleaseRequest.toPayload(): GetRequestPayload =
    GetRequestPayload(releaseId = releaseId, candidateId = candidateId)

private fun CreateReleaseRequest.toPayload(): CreateRequestPayload =
    CreateRequestPayload(
        releaseId = releaseId,
        candidateId = candidateId,
        featureFlagId = featureFlagId,
    )

private fun ValidateReleaseRequest.toPayload(): ValidateRequestPayload =
    ValidateRequestPayload(releaseId = releaseId, actor = actor?.toPayload())

private fun StartReleaseRequest.toPayload(): StartRequestPayload =
    StartRequestPayload(releaseId = releaseId, actor = actor.toPayload())

private fun ContinueReleaseRequest.toPayload(): ContinueRequestPayload =
    ContinueRequestPayload(releaseId = releaseId)

private fun Actor.toPayload(): ActorPayload = ActorPayload(id = id, kind = kind.name, name = name)
