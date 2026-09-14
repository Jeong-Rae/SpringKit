package io.springkit.workflow.adapter.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkflowCliTest :
    FunSpec({
      context("CLI 명령을 Application 요청으로 변환할 때") {
        test("start 필수 옵션을 입력하면, Start 요청을 전달합니다") {
          var request: WorkflowCommandRequest? = null
          val fixture = fixture {
            request = it
            success()
          }
          val result =
              fixture.command.test(
                  "start --task TASK-42 --request-id request-1 --title title --requires sk-101"
              )

          result.statusCode shouldBe 0
          request shouldBe WorkflowCommandRequest.Start("TASK-42", "request-1", "title", "sk-101")
          fixture.stderr.toString() shouldContain "완료되었습니다"
        }

        test("review comment 인자를 입력하면, 본문과 소스 위치를 전달합니다") {
          var request: WorkflowCommandRequest? = null
          val fixture = fixture {
            request = it
            success()
          }
          val result =
              fixture.command.test(
                  "review comment --revision rv-8 --level R --body 의견 --path src/main.kt --line 42"
              )

          result.statusCode shouldBe 0
          request shouldBe
              WorkflowCommandRequest.ReviewComment(
                  revision = "rv-8",
                  level = "R",
                  body = "의견",
                  path = "src/main.kt",
                  line = 42,
              )
        }
      }

      context("JSON 출력 형식을 사용할 때") {
        test("명령이 성공하면, 결정적인 JSON 객체 하나를 표준 출력에 기록합니다") {
          val fixture = fixture { success(buildJsonObject { put("ok", true) }) }
          val result = fixture.command.test("status --subtask sk-102 --json")

          result.statusCode shouldBe 0
          fixture.stdout.toString() shouldBe "{\"data\":{\"ok\":true},\"type\":\"success\"}\n"
          fixture.stderr.toString().shouldBeEmpty()
        }

        test("Gateway가 실패하면, 실패 JSON과 0이 아닌 종료 상태를 반환합니다") {
          val fixture = fixture {
            WorkflowResult.Failure(FailureData(FailureCode.HUMAN_REQUIRED, "사람의 결정이 필요합니다."))
          }
          val result = fixture.command.test("gate approve sk-102 --change-revision cr-4 --json")

          result.statusCode shouldBe 1
          fixture.stdout.toString() shouldBe
              "{\"data\":{\"blocked_by\":[],\"code\":\"HUMAN_REQUIRED\",\"message\":\"사람의 결정이 필요합니다.\",\"next\":[]},\"type\":\"failure\"}\n"
          fixture.stderr.toString() shouldContain "완료하지 못했습니다"
        }
      }
    })

private fun fixture(handler: (WorkflowCommandRequest) -> WorkflowResult<JsonObject>): Fixture {
  val stdout = ByteArrayOutputStream()
  val stderr = ByteArrayOutputStream()
  return Fixture(
      command =
          WorkflowCli(
              gateway = WorkflowCommandGateway(handler),
              stdout = PrintStream(stdout),
              stderr = PrintStream(stderr),
          ),
      stdout = stdout,
      stderr = stderr,
  )
}

private fun success(data: JsonObject = buildJsonObject { put("accepted", true) }) =
    WorkflowResult.Success(data)

private data class Fixture(
    val command: WorkflowCli,
    val stdout: ByteArrayOutputStream,
    val stderr: ByteArrayOutputStream,
)
