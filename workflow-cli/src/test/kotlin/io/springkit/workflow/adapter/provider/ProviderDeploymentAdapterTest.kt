package io.springkit.workflow.adapter.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.CreateCandidateRequest
import io.springkit.workflow.application.GetCandidateRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.PromoteProductionRequest
import io.springkit.workflow.application.StartCanaryRequest
import io.springkit.workflow.application.ValidateCandidateRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.DeploymentCandidateState
import io.springkit.workflow.domain.Risk
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ProviderDeploymentAdapterTest :
    FunSpec({
      context("provider CLI로 배포 후보를 생성하면") {
        test("요청 JSON과 후보 검증 결과를 도메인 객체로 변환합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = true))
              )
          val adapter =
              ProviderDeploymentAdapter(listOf("deployctl", "workflow"), Path.of("/repo"), runner)

          val result =
              adapter.createCandidate(
                  CreateCandidateRequest(
                      mainRevision = "abc123",
                      includedSubTasks = listOf("sk-1"),
                      risks = mapOf("sk-1" to Risk.HIGH),
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.CreateCandidateResponse>()
          response.candidate.id shouldBe "dc-1"
          response.candidate.risk shouldBe Risk.HIGH
          response.candidate.validations.single().status.name shouldBe "PASSED"
          response.change.id shouldBe "change-1"
          runner.commands.single().tokens.take(4) shouldContainExactly
              listOf("deployctl", "workflow", "create-candidate", "--request-json")
          val request = Json.parseToJsonElement(runner.commands.single().tokens.last()).jsonObject
          request["main_revision"].toString() shouldBe "\"abc123\""
          request["included_subtasks"].toString() shouldBe "[\"sk-1\"]"
          request["risks"].toString() shouldBe "{\"sk-1\":\"HIGH\"}"
        }
      }

      context("provider CLI로 배포 후보를 조회하면") {
        test("candidate 응답을 반환하고 조회 명령을 구성합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = false))
              )
          val adapter = ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)

          val result = adapter.getCandidate(GetCandidateRequest("dc-1"))

          val candidate =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.GetCandidateResponse>()
                  .candidate
          candidate.mainRevision shouldBe "abc123"
          runner.commands.single().tokens.take(4) shouldContainExactly
              listOf("deployctl", "get-candidate", "--request-json", "{\"candidate_id\":\"dc-1\"}")
        }
      }

      context("변경 영수증이 없는 provider 응답을 받으면") {
        test("operation과 대상 기반 영수증을 생성합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = false))
              )
          val adapter = ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)

          val result = adapter.startCanary(StartCanaryRequest("dc-1"))

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.StartCanaryResponse>()
          response.change.id shouldBe "provider-deployment-start-canary-dc-1"
          response.change.operation shouldBe "provider-deployment-start-canary"
        }
      }

      context("각 배포 변경 명령을 호출하면") {
        withData(
            nameFn = { (operation, expectedTokens) -> "${operation}을 호출하면, provider 명령을 전달합니다" },
            Triple(
                "validate-candidate",
                listOf("deployctl", "validate-candidate", "--request-json"),
                "validate",
            ),
            Triple(
                "start-canary",
                listOf("deployctl", "start-canary", "--request-json"),
                "start",
            ),
            Triple(
                "promote-production",
                listOf("deployctl", "promote-production", "--request-json"),
                "promote",
            ),
        ) { (operation, expectedTokens, kind) ->
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = true))
              )
          val adapter = ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)

          when (kind) {
            "validate" -> adapter.validate(ValidateCandidateRequest("dc-1", "abc123"))
            "start" -> adapter.startCanary(StartCanaryRequest("dc-1"))
            else ->
                adapter.promoteProduction(
                    PromoteProductionRequest("dc-1", DeploymentCandidateState.CANARY)
                )
          }

          runner.commands.single().tokens.take(3) shouldBe expectedTokens
          runner.commands.single().workingDirectory shouldBe Path.of("/repo")
          operation.isNotBlank() shouldBe true
        }
      }

      context("provider CLI 응답이 올바르지 않으면") {
        test("JSON 오류를 PortError로 변환합니다") {
          val runner = ProviderDeploymentRecordingCommandRunner(CommandResult(0, "{invalid", ""))
          val result =
              ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)
                  .getCandidate(GetCandidateRequest("dc-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_RESPONSE_INVALID"
        }

        test("후보 불변식 오류를 PortError로 변환합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(
                      """
                      {"candidate":{"id":"dc-1","main_revision":"abc123","included_subtasks":["sk-1"],"risks":{"sk-2":"HIGH"},"validations":[],"state":"CANDIDATE"}}
                      """
                          .trimIndent()
                  )
              )
          val result =
              ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)
                  .getCandidate(GetCandidateRequest("dc-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_INVARIANT_VIOLATION"
        }
      }

      context("provider CLI 실행이 실패하면") {
        test("종료 코드와 표준 오류를 PortError로 보존합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(CommandResult(7, "", "provider unavailable"))
          val result =
              ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)
                  .getCandidate(GetCandidateRequest("dc-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_COMMAND_FAILED"
          failure.error.message shouldBe "provider unavailable"
        }

        test("명령 예외를 재시도 가능한 PortError로 변환합니다") {
          val runner =
              ProviderDeploymentFailingCommandRunner(IllegalStateException("not installed"))
          val result =
              ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)
                  .getCandidate(GetCandidateRequest("dc-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.retryable shouldBe true
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_COMMAND_FAILED"
        }
      }

      context("배포 요청 인자가 비어 있으면") {
        test("provider 명령을 실행하지 않고 PortError를 반환합니다") {
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = true))
              )
          val result =
              ProviderDeploymentAdapter(listOf("deployctl"), Path.of("/repo"), runner)
                  .validate(ValidateCandidateRequest("", "abc123"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_REQUEST_INVALID"
          runner.commands shouldBe emptyList()
        }
      }

      context("provider CLI 명령 접두사가 올바르지 않으면") {
        withData(
            nameFn = { "접두사 ${it}이면, 명령 오류를 반환합니다" },
            emptyList<String>(),
            listOf("deployctl", ""),
            listOf(" "),
        ) { prefix ->
          val runner =
              ProviderDeploymentRecordingCommandRunner(
                  commandResult(candidateResponse(withChange = true))
              )
          val result =
              ProviderDeploymentAdapter(prefix, Path.of("/repo"), runner)
                  .getCandidate(GetCandidateRequest("dc-1"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "DEPLOYMENT_PROVIDER_COMMAND_INVALID"
          runner.commands shouldBe emptyList()
        }
      }
    })

private data class ProviderDeploymentInvocation(
    val tokens: List<String>,
    val workingDirectory: Path,
)

private class ProviderDeploymentRecordingCommandRunner(private val result: CommandResult) :
    CommandRunner {
  val commands = mutableListOf<ProviderDeploymentInvocation>()

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += ProviderDeploymentInvocation(command, workingDirectory)
    return result
  }
}

private class ProviderDeploymentFailingCommandRunner(private val failure: Exception) :
    CommandRunner {
  override fun run(command: List<String>, workingDirectory: Path): CommandResult = throw failure
}

private fun commandResult(stdout: String): CommandResult = CommandResult(0, stdout, "")

private fun candidateResponse(withChange: Boolean): String =
    """
    {
      "candidate": {
        "id": "dc-1",
        "main_revision": "abc123",
        "included_subtasks": ["sk-1"],
        "risks": {"sk-1": "HIGH"},
        "validations": [
          {"id": "validation-1", "name": "merge-validation", "status": "PASSED", "required": true, "revision": "abc123"}
        ],
        "state": "CANDIDATE"
      }${if (withChange) ",\"change\": {\"id\": \"change-1\", \"operation\": \"provider-create\", \"status\": \"APPLIED\", \"before_revision\": \"old\", \"after_revision\": \"abc123\"}" else ""}
    }
    """
        .trimIndent()
