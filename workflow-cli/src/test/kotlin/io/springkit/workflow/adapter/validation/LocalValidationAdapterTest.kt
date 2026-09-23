package io.springkit.workflow.adapter.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.GetValidationRequest
import io.springkit.workflow.application.GetValidationResponse
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.RunValidationRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.CheckResult
import io.springkit.workflow.domain.Validation
import io.springkit.workflow.domain.ValidationStatus
import java.nio.file.Path

class LocalValidationAdapterTest :
    FunSpec({
      context("필수 검증을 실행하면") {
        test("test와 build를 입력 순서대로 실행하면, 각 결과와 CheckSummary를 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "test passed", ""))
          runner.enqueue(CommandResult(0, "build passed", ""))
          val workspace = Path.of("/workspace/sk-101")
          val adapter = LocalValidationAdapter({ workspaceId -> workspace }, commandRunner = runner)
          val request = request()

          val result = adapter.run(request).shouldBeTypeOf<PortResult.Success<*>>().value
          val response =
              result.shouldBeTypeOf<io.springkit.workflow.application.RunValidationResponse>()

          response.validations.map { it.status } shouldBe
              listOf(ValidationStatus.PASSED, ValidationStatus.PASSED)
          response.summary?.checks?.map { it.status } shouldBe
              listOf(ValidationStatus.PASSED, ValidationStatus.PASSED)
          runner.invocations shouldContainExactly
              listOf(
                  Invocation(listOf("./gradlew", "test"), workspace),
                  Invocation(listOf("./gradlew", "build"), workspace),
              )
        }

        test("명령 토큰에 공백이 포함되어 있으면, 셸을 거치지 않고 토큰 목록을 전달합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(0, "", ""))
          val workspace = Path.of("/workspace/sk 101")
          val adapter =
              LocalValidationAdapter(
                  workspacePathResolver = { workspace },
                  validationCommands =
                      mapOf("test" to listOf("./gradlew", "test", "--tests", "A B")),
                  commandRunner = runner,
              )

          adapter.run(request(required = listOf(validation("test"))))

          runner.invocations shouldBe
              listOf(Invocation(listOf("./gradlew", "test", "--tests", "A B"), workspace))
        }
      }

      context("검증 명령이 실패하면") {
        test("종료 코드가 0이 아니면, 해당 Validation과 CheckResult를 FAILED로 반환합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueue(CommandResult(7, "", "tests failed"))
          val adapter = LocalValidationAdapter({ Path.of("/workspace") }, commandRunner = runner)

          val response =
              adapter
                  .run(request(required = listOf(validation("test"))))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.RunValidationResponse>()

          response.validations.single().status shouldBe ValidationStatus.FAILED
          response.validations.single().message shouldContain "tests failed"
          response.summary?.checks?.single()?.status shouldBe ValidationStatus.FAILED
        }

        test("첫 번째 명령이 예외를 발생시키면, 해당 검증을 실패로 기록하고 다음 명령을 실행합니다") {
          val runner = RecordingCommandRunner()
          runner.enqueueFailure()
          runner.enqueue(CommandResult(0, "build passed", ""))
          val workspace = Path.of("/workspace")
          val adapter = LocalValidationAdapter({ workspace }, commandRunner = runner)

          val response =
              adapter
                  .run(request())
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.RunValidationResponse>()

          response.validations.map { it.status } shouldBe
              listOf(ValidationStatus.FAILED, ValidationStatus.PASSED)
          response.validations.first().message shouldContain "검증 명령 실행 중 예외"
          runner.invocations shouldContainExactly
              listOf(
                  Invocation(listOf("./gradlew", "test"), workspace),
                  Invocation(listOf("./gradlew", "build"), workspace),
              )
        }
      }

      context("Adapter 설정과 workspace를 확인하면") {
        test("검증 명령이 매핑되지 않으면, 명령을 실행하지 않고 PortResult.Failure를 반환합니다") {
          val runner = RecordingCommandRunner()
          val adapter =
              LocalValidationAdapter(
                  workspacePathResolver = { Path.of("/workspace") },
                  validationCommands = emptyMap(),
                  commandRunner = runner,
              )

          val failure =
              adapter
                  .run(request(required = listOf(validation("lint"))))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "VALIDATION_COMMAND_NOT_CONFIGURED"
          runner.invocations shouldBe emptyList()
        }

        test("workspace 경로 조회가 예외를 발생시키면, 실행하지 않고 PortResult.Failure를 반환합니다") {
          val runner = RecordingCommandRunner()
          val adapter =
              LocalValidationAdapter({ error("workspace unavailable") }, commandRunner = runner)

          val failure =
              adapter
                  .run(request(required = listOf(validation("test"))))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "WORKSPACE_PATH_UNAVAILABLE"
          runner.invocations shouldBe emptyList()
        }
      }

      context("기존 검증 결과를 조회하면") {
        test("조회 함수를 주입하면, get 결과를 그대로 반환합니다") {
          val expected =
              PortResult.Success(
                  GetValidationResponse(
                      validations = listOf(validation("test", ValidationStatus.PASSED)),
                      checks =
                          listOf(
                              CheckResult(
                                  id = "test",
                                  name = "test",
                                  status = ValidationStatus.PASSED,
                                  fingerprint = "fingerprint-1",
                                  revision = "revision-1",
                              )
                          ),
                  )
              )
          var requested: GetValidationRequest? = null
          val adapter =
              LocalValidationAdapter(
                  workspacePathResolver = { Path.of("/workspace") },
                  existingResultLookup = {
                    requested = it
                    expected
                  },
              )
          val request = GetValidationRequest("workspace-1", "revision-1", "fingerprint-1")

          adapter.get(request) shouldBe expected
          requested shouldBe request
        }

        test("조회 함수를 주입하지 않으면, 빈 검증 결과를 반환합니다") {
          val result =
              LocalValidationAdapter({ Path.of("/workspace") })
                  .get(GetValidationRequest(revision = "revision-1"))

          result shouldBe PortResult.Success(GetValidationResponse(emptyList(), emptyList()))
        }
      }
    })

private fun request(required: List<Validation> = listOf(validation("test"), validation("build"))) =
    RunValidationRequest(
        workspaceId = "workspace-1",
        revision = "revision-1",
        fingerprint = "fingerprint-1",
        required = required,
    )

private fun validation(name: String, status: ValidationStatus = ValidationStatus.PENDING) =
    Validation(id = name, name = name, status = status)

private data class Invocation(val command: List<String>, val workingDirectory: Path)

private class RecordingCommandRunner : CommandRunner {
  val invocations = mutableListOf<Invocation>()
  private val results = ArrayDeque<() -> CommandResult>()

  fun enqueue(result: CommandResult) {
    results.addLast { result }
  }

  fun enqueueFailure() {
    results.addLast { error("runner failed") }
  }

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    invocations += Invocation(command, workingDirectory)
    return results.removeFirst().invoke()
  }
}
