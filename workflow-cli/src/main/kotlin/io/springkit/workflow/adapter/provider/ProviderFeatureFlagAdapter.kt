package io.springkit.workflow.adapter.provider

import io.springkit.workflow.application.FeatureFlagPort
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ValidateFeatureFlagRequest
import io.springkit.workflow.application.ValidateFeatureFlagResponse
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 설치된 provider CLI에 Feature Flag 검증을 위임하는 어댑터입니다. */
class ProviderFeatureFlagAdapter(
    commandPrefix: List<String>,
    private val workingDirectory: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
) : FeatureFlagPort {
  private val commandPrefix: List<String> = commandPrefix.toList()

  override fun validateDefault(
      request: ValidateFeatureFlagRequest
  ): PortResult<ValidateFeatureFlagResponse> {
    if (request.featureFlagId.isBlank()) {
      return failure(
          code = "FEATURE_FLAG_REQUEST_INVALID",
          message = "Feature Flag 식별자가 비어 있습니다.",
      )
    }
    if (commandPrefix.isEmpty() || commandPrefix.any(String::isBlank)) {
      return failure(
          code = "FEATURE_FLAG_COMMAND_INVALID",
          message = "Feature Flag provider CLI 명령 접두사가 비어 있거나 공백입니다.",
          target = request.featureFlagId,
      )
    }

    val command =
        commandPrefix +
            listOf(
                "validate-feature-flag",
                "--request-json",
                json.encodeToString(FeatureFlagRequestPayload(request.featureFlagId)),
            )
    val result =
        try {
          commandRunner.run(command, workingDirectory)
        } catch (failure: Exception) {
          return failure(
              code = "FEATURE_FLAG_COMMAND_FAILED",
              message =
                  "Feature Flag provider CLI 실행 중 예외가 발생했습니다: " +
                      (failure.message ?: failure::class.simpleName.orEmpty()),
              target = request.featureFlagId,
              retryable = true,
          )
        }
    if (result.exitCode != 0) {
      return failure(
          code = "FEATURE_FLAG_COMMAND_FAILED",
          message =
              result.stderr
                  .trim()
                  .ifBlank { result.stdout.trim() }
                  .ifBlank {
                    "Feature Flag provider CLI가 종료 코드 ${result.exitCode}로 실패했습니다."
                  },
          target = request.featureFlagId,
          retryable = result.exitCode == 2,
      )
    }

    val response =
        try {
          json.decodeFromString<FeatureFlagResponsePayload>(result.stdout)
        } catch (_: SerializationException) {
          return failure(
              code = "FEATURE_FLAG_RESPONSE_INVALID",
              message = "Feature Flag provider CLI 응답 JSON을 해석할 수 없습니다.",
              target = request.featureFlagId,
          )
        } catch (_: IllegalArgumentException) {
          return failure(
              code = "FEATURE_FLAG_RESPONSE_INVALID",
              message = "Feature Flag provider CLI 응답 JSON이 올바르지 않습니다.",
              target = request.featureFlagId,
          )
        }
    return PortResult.Success(ValidateFeatureFlagResponse(response.safeDefault))
  }

  private fun failure(
      code: String,
      message: String,
      target: String? = null,
      retryable: Boolean = false,
  ): PortResult.Failure =
      PortResult.Failure(
          PortError(code = code, message = message, retryable = retryable, target = target)
      )

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}

@Serializable
private data class FeatureFlagRequestPayload(
    @SerialName("feature_flag_id") val featureFlagId: String,
)

@Serializable
private data class FeatureFlagResponsePayload(
    @SerialName("safe_default") val safeDefault: Boolean,
)
