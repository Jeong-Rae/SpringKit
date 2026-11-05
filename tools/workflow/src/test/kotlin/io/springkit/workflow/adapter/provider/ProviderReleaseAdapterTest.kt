package io.springkit.workflow.adapter.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.ChangeStatus
import io.springkit.workflow.application.ContinueReleaseRequest
import io.springkit.workflow.application.CreateReleaseRequest
import io.springkit.workflow.application.GetReleaseRequest
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StartReleaseRequest
import io.springkit.workflow.application.ValidateReleaseRequest
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProviderReleaseAdapterTest :
    FunSpec({
      context("외부 공개 provider CLI에 Release 요청을 전달하면") {
        test("operation과 request JSON을 전달하면, Release로 변환하고 하나의 인자로 명령을 구성합니다") {
          val runner =
              ProviderReleaseRecordingCommandRunner(resultFor("{\"release\":${releaseJson()}}"))
          val adapter =
              ProviderReleaseAdapter(
                  listOf("release-provider", "--repo", "sample"),
                  Path.of("/repo"),
                  runner,
              )

          val result =
              adapter.create(
                  CreateReleaseRequest(
                      releaseId = "rel-1",
                      candidateId = "dc-1",
                  )
              )

          val response = result.shouldBeTypeOf<PortResult.Success<*>>().value
          response
              .shouldBeTypeOf<io.springkit.workflow.application.CreateReleaseResponse>()
              .release
              .id shouldBe "rel-1"
          runner.commands.single().tokens.take(4) shouldContainExactly
              listOf("release-provider", "--repo", "sample", "create-release")
          runner.commands.single().tokens[4] shouldBe "--request-json"
          val request = Json.parseToJsonElement(runner.commands.single().tokens[5]).jsonObject
          request["release_id"]?.jsonPrimitive?.content shouldBe "rel-1"
          request["candidate_id"]?.jsonPrimitive?.content shouldBe "dc-1"
          request["feature_flag_id"] shouldBe null
        }

        test("actor가 있으면, 최소 actor 필드를 snake_case 요청 JSON에 포함합니다") {
          val runner = ProviderReleaseRecordingCommandRunner(resultFor(releaseJson()))
          val adapter = ProviderReleaseAdapter(listOf("provider"), Path.of("/repo"), runner)

          adapter.start(
              StartReleaseRequest(
                  releaseId = "rel-1",
                  actor = Actor("human-1", ActorKind.HUMAN, "홍길동"),
              )
          )

          val request = Json.parseToJsonElement(runner.commands.single().tokens.last()).jsonObject
          val actor = request["actor"]!!.jsonObject
          actor["id"]?.jsonPrimitive?.content shouldBe "human-1"
          actor["kind"]?.jsonPrimitive?.content shouldBe "HUMAN"
          actor["name"]?.jsonPrimitive?.content shouldBe "홍길동"
        }

        test("provider change가 있으면, receipt의 상태와 revision을 보존합니다") {
          val runner =
              ProviderReleaseRecordingCommandRunner(
                  resultFor(
                      """
                      {"release":${releaseJson()},"change":{"id":"change-1","operation":"provider-start","status":"PENDING","before_revision":"r1","after_revision":"r2"}}
                      """
                          .trimIndent()
                  )
              )
          val adapter = ProviderReleaseAdapter(listOf("provider"), Path.of("/repo"), runner)

          val response =
              adapter
                  .start(StartReleaseRequest("rel-1", Actor("workflow", ActorKind.WORKFLOW)))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.StartReleaseResponse>()

          response.change.id shouldBe "change-1"
          response.change.status shouldBe ChangeStatus.PENDING
          response.change.beforeRevision shouldBe "r1"
          response.change.afterRevision shouldBe "r2"
        }

        test("provider change가 없으면, 작업별 receipt를 생성합니다") {
          val runner =
              ProviderReleaseRecordingCommandRunner(resultFor("{\"release\":${releaseJson()}}"))
          val adapter = ProviderReleaseAdapter(listOf("provider"), Path.of("/repo"), runner)

          val response =
              adapter
                  .continueRollout(ContinueReleaseRequest("rel-1"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.ContinueReleaseResponse>()

          response.change.id shouldBe "provider-release-continue-rollout-rel-1"
          response.change.operation shouldBe "provider-release-continue-rollout"
        }
      }

      context("외부 공개 provider 응답을 해석하면") {
        test("get 응답에 releases 배열이 있으면, Release 목록으로 변환합니다") {
          val runner =
              ProviderReleaseRecordingCommandRunner(
                  resultFor("{\"releases\":[${releaseJson()},${releaseJson("rel-2")}]}")
              )
          val adapter = ProviderReleaseAdapter(listOf("provider"), Path.of("/repo"), runner)

          val response =
              adapter
                  .get(GetReleaseRequest(candidateId = "dc-1"))
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<io.springkit.workflow.application.GetReleaseResponse>()

          response.releases.map { it.id } shouldBe listOf("rel-1", "rel-2")
          val request = Json.parseToJsonElement(runner.commands.single().tokens.last()).jsonObject
          request["candidate_id"]?.jsonPrimitive?.content shouldBe "dc-1"
          request.containsKey("release_id") shouldBe false
        }

        test("JSON이 유효하지 않으면, 응답 오류를 반환합니다") {
          val adapter =
              ProviderReleaseAdapter(
                  listOf("provider"),
                  Path.of("/repo"),
                  ProviderReleaseRecordingCommandRunner(resultFor("not-json")),
              )

          val failure =
              adapter
                  .get(GetReleaseRequest(releaseId = "rel-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_RESPONSE_INVALID"
        }

        test("Release 대상이 요청과 다르면, 불변식 오류를 반환합니다") {
          val adapter =
              ProviderReleaseAdapter(
                  listOf("provider"),
                  Path.of("/repo"),
                  ProviderReleaseRecordingCommandRunner(
                      resultFor("{\"release\":${releaseJson("other")}}")
                  ),
              )

          val failure =
              adapter
                  .validateInternal(ValidateReleaseRequest("rel-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_INVARIANT_VIOLATION"
        }

        test("change 필수 필드가 누락되면, 응답 오류를 반환합니다") {
          val adapter =
              ProviderReleaseAdapter(
                  listOf("provider"),
                  Path.of("/repo"),
                  ProviderReleaseRecordingCommandRunner(
                      resultFor(
                          "{\"release\":${releaseJson()},\"change\":{\"id\":\"change-1\",\"operation\":\"provider-create\"}}"
                      )
                  ),
              )

          val failure =
              adapter
                  .create(CreateReleaseRequest("rel-1", "dc-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_RESPONSE_INVALID"
        }
      }

      context("외부 공개 provider 명령이 실패하면") {
        withData(
            nameFn = { (exitCode, retryable) ->
              "종료 코드가 ${exitCode}이면, 재시도 가능 여부를 ${retryable}로 반환합니다"
            },
            1 to false,
            2 to true,
            3 to false,
        ) { (exitCode, retryable) ->
          val adapter =
              ProviderReleaseAdapter(
                  listOf("provider"),
                  Path.of("/repo"),
                  ProviderReleaseRecordingCommandRunner(
                      CommandResult(exitCode, "", "provider unavailable")
                  ),
              )

          val failure =
              adapter
                  .get(GetReleaseRequest(releaseId = "rel-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_COMMAND_FAILED"
          failure.error.message shouldBe "provider unavailable"
          failure.error.target shouldBe "rel-1"
          failure.error.retryable shouldBe retryable
        }

        test("CommandRunner가 예외를 던지면, 재시도 가능한 PortError로 변환합니다") {
          val adapter =
              ProviderReleaseAdapter(
                  listOf("provider"),
                  Path.of("/repo"),
                  CommandRunner { _, _ -> error("provider unavailable") },
              )

          val failure =
              adapter
                  .get(GetReleaseRequest(releaseId = "rel-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_COMMAND_FAILED"
          failure.error.retryable shouldBe true
        }
      }

      context("외부 공개 provider 요청을 검증하면") {
        test("명령 접두사에 빈 항목이 있으면, provider 명령을 실행하지 않습니다") {
          val runner = ProviderReleaseRecordingCommandRunner(CommandResult(0, "", ""))
          val adapter = ProviderReleaseAdapter(listOf("provider", ""), Path.of("/repo"), runner)

          val failure =
              adapter
                  .get(GetReleaseRequest(releaseId = "rel-1"))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_COMMAND_INVALID"
          runner.commands shouldBe emptyList()
        }

        test("Release 식별자가 비어 있으면, provider 명령을 실행하지 않습니다") {
          val runner = ProviderReleaseRecordingCommandRunner(CommandResult(0, "", ""))
          val adapter = ProviderReleaseAdapter(listOf("provider"), Path.of("/repo"), runner)

          val failure =
              adapter
                  .continueRollout(ContinueReleaseRequest(""))
                  .shouldBeTypeOf<PortResult.Failure>()

          failure.error.code shouldBe "RELEASE_PROVIDER_REQUEST_INVALID"
          runner.commands shouldBe emptyList()
        }
      }
    })

private class ProviderReleaseRecordingCommandRunner(private val result: CommandResult) :
    CommandRunner {
  val commands = mutableListOf<ProviderReleaseInvocation>()

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += ProviderReleaseInvocation(command, workingDirectory)
    return result
  }
}

private data class ProviderReleaseInvocation(
    val tokens: List<String>,
    val workingDirectory: Path,
)

private fun resultFor(stdout: String): CommandResult = CommandResult(0, stdout, "")

private fun releaseJson(id: String = "rel-1"): String =
    """
    {"release_id":"$id","candidate_id":"dc-1","state":"SAFE_DEFAULT","production_ready":true,"internal_validation_passed":true}
    """
        .trimIndent()
