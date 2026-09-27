package io.springkit.workflow.adapter.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.ValidateFeatureFlagRequest
import io.springkit.workflow.application.ValidateFeatureFlagResponse
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import java.nio.file.Path

class ProviderFeatureFlagAdapterTest :
    FunSpec({
      context("Feature Flag 기본 동작을 provider CLI로 검증하면") {
        test("JSON 요청과 응답을 입력하면, FeatureFlagPort 결과로 변환합니다") {
          val runner =
              ProviderFeatureFlagRecordingCommandRunner(
                  CommandResult(0, "{\"safe_default\":true}", "")
              )
          val adapter =
              ProviderFeatureFlagAdapter(
                  listOf("provider", "workflow"),
                  Path.of("/repo"),
                  runner,
              )

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("recommendation-v2"))

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<ValidateFeatureFlagResponse>()
              .safeDefault shouldBe true
          runner.commands.single().command shouldContainExactly
              listOf(
                  "provider",
                  "workflow",
                  "validate-feature-flag",
                  "--request-json",
                  "{\"feature_flag_id\":\"recommendation-v2\"}",
              )
          runner.commands.single().workingDirectory shouldBe Path.of("/repo")
        }

        test("안전 기본 동작이 false이면, false 결과를 보존합니다") {
          val runner =
              ProviderFeatureFlagRecordingCommandRunner(
                  CommandResult(0, "{\"safe_default\":false}", "")
              )
          val adapter = ProviderFeatureFlagAdapter(listOf("provider"), Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("recommendation-v2"))

          result
              .shouldBeTypeOf<PortResult.Success<*>>()
              .value
              .shouldBeTypeOf<ValidateFeatureFlagResponse>()
              .safeDefault shouldBe false
        }
      }

      context("provider CLI 명령 접두사를 검증하면") {
        withData(
            nameFn = {
              "명령 접두사가 ${it}이면, FEATURE_FLAG_COMMAND_INVALID 오류를 반환합니다"
            },
            emptyList<String>(),
            listOf("provider", ""),
            listOf(" "),
        ) { prefix ->
          val runner = ProviderFeatureFlagRecordingCommandRunner()
          val adapter = ProviderFeatureFlagAdapter(prefix, Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("flag-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "FEATURE_FLAG_COMMAND_INVALID"
          runner.commands shouldBe emptyList()
        }
      }

      context("Feature Flag 요청을 검증하면") {
        test("식별자가 비어 있으면, provider 명령을 실행하지 않습니다") {
          val runner = ProviderFeatureFlagRecordingCommandRunner()
          val adapter = ProviderFeatureFlagAdapter(listOf("provider"), Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest(""))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "FEATURE_FLAG_REQUEST_INVALID"
          runner.commands shouldBe emptyList()
        }
      }

      context("provider CLI 응답을 처리하면") {
        test("JSON이 올바르지 않으면, FEATURE_FLAG_RESPONSE_INVALID 오류를 반환합니다") {
          val runner = ProviderFeatureFlagRecordingCommandRunner(CommandResult(0, "not-json", ""))
          val adapter = ProviderFeatureFlagAdapter(listOf("provider"), Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("flag-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "FEATURE_FLAG_RESPONSE_INVALID"
          failure.error.message shouldContain "응답 JSON"
        }

        withData(
            nameFn = { (exitCode, retryable) ->
              "종료 코드가 ${exitCode}이면, 재시도 가능 여부를 ${retryable}로 반환합니다"
            },
            1 to false,
            2 to true,
            3 to false,
        ) { (exitCode, retryable) ->
          val runner =
              ProviderFeatureFlagRecordingCommandRunner(
                  CommandResult(exitCode, "", "provider unavailable")
              )
          val adapter = ProviderFeatureFlagAdapter(listOf("provider"), Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("flag-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "FEATURE_FLAG_COMMAND_FAILED"
          failure.error.message shouldContain "provider unavailable"
          failure.error.retryable shouldBe retryable
        }

        test("명령 실행에서 예외가 발생하면, FEATURE_FLAG_COMMAND_FAILED 오류를 반환합니다") {
          val runner =
              ProviderFeatureFlagThrowingCommandRunner(
                  IllegalStateException("provider is unavailable")
              )
          val adapter = ProviderFeatureFlagAdapter(listOf("provider"), Path.of("/repo"), runner)

          val result = adapter.validateDefault(ValidateFeatureFlagRequest("flag-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "FEATURE_FLAG_COMMAND_FAILED"
          failure.error.message shouldContain "provider is unavailable"
          failure.error.retryable shouldBe true
        }
      }
    })

private data class ProviderFeatureFlagCommandInvocation(
    val command: List<String>,
    val workingDirectory: Path,
)

private class ProviderFeatureFlagRecordingCommandRunner(
    private val result: CommandResult = CommandResult(0, "", "")
) : CommandRunner {
  val commands = mutableListOf<ProviderFeatureFlagCommandInvocation>()

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += ProviderFeatureFlagCommandInvocation(command, workingDirectory)
    return result
  }
}

private class ProviderFeatureFlagThrowingCommandRunner(private val failure: Exception) :
    CommandRunner {
  override fun run(command: List<String>, workingDirectory: Path): CommandResult = throw failure
}
