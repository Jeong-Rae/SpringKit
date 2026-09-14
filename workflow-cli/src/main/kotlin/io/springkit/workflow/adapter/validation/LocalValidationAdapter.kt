package io.springkit.workflow.adapter.validation

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.GetValidationRequest
import io.springkit.workflow.application.GetValidationResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.RunValidationRequest
import io.springkit.workflow.application.RunValidationResponse
import io.springkit.workflow.application.ValidationPort
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.CheckSummary
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import io.springkit.workflow.domain.WorkspaceId
import java.nio.file.Path

/** 로컬 Gradle 명령에 검증 실행을 위임하는 어댑터입니다. */
class LocalValidationAdapter(
    private val workspacePathResolver: (WorkspaceId) -> Path,
    private val validationCommands: Map<String, List<String>> = DEFAULT_COMMANDS,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
    private val existingResultLookup:
        ((GetValidationRequest) -> PortResult<GetValidationResponse>)? =
        null,
) : ValidationPort {
  override fun run(request: RunValidationRequest): PortResult<RunValidationResponse> {
    val workspacePath =
        resolveWorkspace(request.workspaceId) ?: return workspacePathFailure(request)
    val validations = mutableListOf<Validation>()

    for (required in request.required) {
      val command = validationCommands[required.name]
      if (command == null || command.isEmpty() || command.any { it.isBlank() }) {
        return PortResult.Failure(
            PortError(
                code = "VALIDATION_COMMAND_NOT_CONFIGURED",
                message = "검증 명령을 확인할 수 없습니다: ${required.name}",
                target = required.name,
            )
        )
      }

      val execution = runCatching { commandRunner.run(command, workspacePath) }
      validations +=
          execution.fold(
              onSuccess = { required.toValidation(it, request.revision) },
              onFailure = { required.failedValidation(request.revision, it) },
          )
    }

    val checks = validations.map { validation ->
      CheckResult(
          id = validation.id,
          name = validation.name,
          status = validation.status,
          fingerprint = request.fingerprint,
          revision = request.revision,
          message = validation.message,
      )
    }
    return PortResult.Success(
        RunValidationResponse(
            validations = validations,
            summary = CheckSummary(request.fingerprint, request.revision, checks),
            change =
                ChangeReceipt(
                    id = "validation-${request.workspaceId}-${request.fingerprint}",
                    operation = "run-validation",
                ),
        )
    )
  }

  override fun get(request: GetValidationRequest): PortResult<GetValidationResponse> =
      existingResultLookup?.invoke(request)
          ?: PortResult.Success(GetValidationResponse(emptyList(), emptyList()))

  private fun resolveWorkspace(workspaceId: WorkspaceId): Path? =
      try {
        workspacePathResolver(workspaceId)
      } catch (_: Exception) {
        null
      }

  private fun workspacePathFailure(request: RunValidationRequest): PortResult.Failure =
      PortResult.Failure(
          PortError(
              code = "WORKSPACE_PATH_UNAVAILABLE",
              message = "workspace 경로를 확인할 수 없습니다: ${request.workspaceId}",
              target = request.workspaceId,
          )
      )

  private fun Validation.toValidation(result: CommandResult, revision: String): Validation =
      if (result.exitCode == 0) {
        copy(status = ValidationStatus.PASSED, revision = revision, message = null)
      } else {
        copy(
            status = ValidationStatus.FAILED,
            revision = revision,
            message = result.failureMessage(),
        )
      }

  private fun Validation.failedValidation(revision: String, exception: Throwable): Validation =
      copy(
          status = ValidationStatus.FAILED,
          revision = revision,
          message = "검증 명령 실행 중 예외가 발생했습니다: ${exception.message ?: exception::class.simpleName}",
      )

  private fun CommandResult.failureMessage(): String {
    val output = stderr.trim().ifBlank { stdout.trim() }
    return if (output.isBlank()) {
      "명령이 종료 코드 ${exitCode}로 실패했습니다."
    } else {
      "명령이 종료 코드 ${exitCode}로 실패했습니다: $output"
    }
  }

  companion object {
    val DEFAULT_COMMANDS: Map<String, List<String>> =
        mapOf(
            "test" to listOf("./gradlew", "test"),
            "build" to listOf("./gradlew", "build"),
        )
  }
}
