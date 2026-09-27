package io.springkit.workflow.adapter.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
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

        withData(
            nameFn = { "${it.name} 명령을 입력하면, 해당 Application 요청을 전달합니다" },
            CommandCase("check", "check", WorkflowCommandRequest.Check),
            CommandCase(
                "review open",
                "review open --body-file pr.md --risk high",
                WorkflowCommandRequest.ReviewOpen("pr.md", "high"),
            ),
            CommandCase(
                "review show",
                "review show --diff --threads all",
                WorkflowCommandRequest.ReviewShow(diff = true, threads = "all"),
            ),
            CommandCase(
                "review update",
                "review update --revision rv-8 --body-file pr.md",
                WorkflowCommandRequest.ReviewUpdate("rv-8", "pr.md"),
            ),
            CommandCase(
                "review update mismatch override",
                "review update --revision rv-8 --body-file pr.md --allow-account-mismatch",
                WorkflowCommandRequest.ReviewUpdate("rv-8", "pr.md", allowAccountMismatch = true),
            ),
            CommandCase(
                "review comment mismatch override",
                "review comment --revision rv-8 --level R --body 의견 --allow-account-mismatch",
                WorkflowCommandRequest.ReviewComment(
                    revision = "rv-8",
                    level = "R",
                    body = "의견",
                    allowAccountMismatch = true,
                ),
            ),
            CommandCase(
                "review reply",
                "review reply --revision rv-8 --thread thread-1 --body 답변",
                WorkflowCommandRequest.ReviewReply("rv-8", "thread-1", body = "답변"),
            ),
            CommandCase(
                "review reply mismatch override",
                "review reply --revision rv-8 --thread thread-1 --body 답변 --allow-account-mismatch",
                WorkflowCommandRequest.ReviewReply(
                    "rv-8",
                    "thread-1",
                    body = "답변",
                    allowAccountMismatch = true,
                ),
            ),
            CommandCase(
                "review resolve",
                "review resolve --revision rv-8 --thread thread-1",
                WorkflowCommandRequest.ReviewResolve("rv-8", "thread-1"),
            ),
            CommandCase(
                "review resolve mismatch override",
                "review resolve --revision rv-8 --thread thread-1 --allow-account-mismatch",
                WorkflowCommandRequest.ReviewResolve(
                    "rv-8",
                    "thread-1",
                    allowAccountMismatch = true,
                ),
            ),
            CommandCase(
                "stack requires",
                "stack --requires sk-101",
                WorkflowCommandRequest.Stack(requires = "sk-101"),
            ),
            CommandCase(
                "stack mismatch override",
                "stack --requires sk-101 --allow-account-mismatch",
                WorkflowCommandRequest.Stack(requires = "sk-101", allowAccountMismatch = true),
            ),
            CommandCase(
                "stack clear",
                "stack --clear",
                WorkflowCommandRequest.Stack(clear = true),
            ),
            CommandCase("sync", "sync", WorkflowCommandRequest.Sync()),
            CommandCase(
                "sync continue",
                "sync --continue",
                WorkflowCommandRequest.Sync(continueSync = true),
            ),
            CommandCase(
                "sync mismatch override",
                "sync --continue --allow-account-mismatch",
                WorkflowCommandRequest.Sync(continueSync = true, allowAccountMismatch = true),
            ),
            CommandCase(
                "sync abort",
                "sync --abort",
                WorkflowCommandRequest.Sync(abort = true),
            ),
            CommandCase(
                "status",
                "status --candidate dc-1",
                WorkflowCommandRequest.Status(candidate = "dc-1"),
            ),
            CommandCase(
                "gate ready",
                "gate ready sk-101 --review-revision rv-8",
                WorkflowCommandRequest.GateReady("sk-101", "rv-8"),
            ),
            CommandCase(
                "gate ready mismatch override",
                "gate ready sk-101 --review-revision rv-8 --allow-account-mismatch",
                WorkflowCommandRequest.GateReady("sk-101", "rv-8", allowAccountMismatch = true),
            ),
            CommandCase(
                "gate approve",
                "gate approve sk-101 --change-revision cr-4",
                WorkflowCommandRequest.GateApprove("sk-101", "cr-4"),
            ),
            CommandCase(
                "gate approve mismatch override",
                "gate approve sk-101 --change-revision cr-4 --allow-account-mismatch",
                WorkflowCommandRequest.GateApprove("sk-101", "cr-4", allowAccountMismatch = true),
            ),
            CommandCase(
                "gate deploy",
                "gate deploy dc-1",
                WorkflowCommandRequest.GateDeploy("dc-1"),
            ),
            CommandCase(
                "gate release",
                "gate release rel-1",
                WorkflowCommandRequest.GateRelease("rel-1"),
            ),
        ) { case ->
          var request: WorkflowCommandRequest? = null
          val fixture = fixture {
            request = it
            success()
          }

          val result = fixture.command.test(case.arguments)

          result.statusCode shouldBe 0
          request shouldBe case.expected
        }
      }

      context("지원하지 않는 CLI 인자를 검증할 때") {
        withData(
            nameFn = { "${it.name}, 요청을 전달하지 않고 실패합니다" },
            InvalidCommandCase(
                "review open의 risk가 지원 값이 아니면",
                "review open --body-file pr.md --risk medium",
            ),
            InvalidCommandCase(
                "review open에서 Feature Flag 옵션을 입력하면",
                "review open --body-file pr.md --risk normal --feature-flag flag-1",
            ),
            InvalidCommandCase(
                "review open에서 exposure 옵션을 입력하면",
                "review open --body-file pr.md --risk normal --exposure unchanged",
            ),
            InvalidCommandCase(
                "PR 작성자를 비교할 수 없는 review open에는 override를 쓸 수 없으면",
                "review open --body-file pr.md --risk normal --allow-account-mismatch",
            ),
            InvalidCommandCase(
                "조회 명령 review show에 mismatch override를 입력하면",
                "review show --threads all --allow-account-mismatch",
            ),
            InvalidCommandCase(
                "조회 명령 status에 mismatch override를 입력하면",
                "status --candidate dc-1 --allow-account-mismatch",
            ),
            InvalidCommandCase(
                "review comment에 본문 입력이 없으면",
                "review comment --revision rv-8 --level R",
            ),
            InvalidCommandCase(
                "review comment에 두 본문 입력이 모두 있으면",
                "review comment --revision rv-8 --level R --body 본문 --body-file comment.md",
            ),
            InvalidCommandCase(
                "review comment에 path만 있으면",
                "review comment --revision rv-8 --level R --body 본문 --path src/main.kt",
            ),
            InvalidCommandCase(
                "review comment에 line만 있으면",
                "review comment --revision rv-8 --level R --body 본문 --line 42",
            ),
            InvalidCommandCase(
                "review comment의 level이 지원 값이 아니면",
                "review comment --revision rv-8 --level B --body 본문",
            ),
            InvalidCommandCase(
                "review reply에 본문 입력이 없으면",
                "review reply --revision rv-8 --thread thread-1",
            ),
            InvalidCommandCase(
                "review reply에 두 본문 입력이 모두 있으면",
                "review reply --revision rv-8 --thread thread-1 --body 답변 --body-file reply.md",
            ),
            InvalidCommandCase("stack 선택이 없으면", "stack"),
            InvalidCommandCase("stack 선택이 둘 다 있으면", "stack --requires sk-101 --clear"),
            InvalidCommandCase("sync 복구 선택이 둘 다 있으면", "sync --continue --abort"),
            InvalidCommandCase(
                "status 대상 선택이 둘 이상이면",
                "status --subtask sk-101 --task TASK-42",
            ),
        ) { case ->
          var request: WorkflowCommandRequest? = null
          val fixture = fixture {
            request = it
            success()
          }

          val result = fixture.command.test(case.arguments)

          result.statusCode shouldBe 1
          request shouldBe null
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

        test("--json과 함께 필수 옵션을 누락하면, 실패 JSON 객체 하나를 표준 출력에 기록합니다") {
          val fixture = fixture { success() }

          val result = fixture.command.test("start --json")

          result.statusCode shouldBe 1
          fixture.stdout.toString().lineSequence().filter(String::isNotBlank).count() shouldBe 1
          fixture.stdout.toString() shouldContain "\"type\":\"failure\""
          fixture.stdout.toString() shouldContain "\"code\":\"INVALID_ARGUMENT\""
          fixture.stderr.toString().shouldBeEmpty()
        }

        test("--json과 함께 지원하지 않는 인자를 입력하면, 실패 JSON 객체 하나를 표준 출력에 기록합니다") {
          val fixture = fixture { success() }

          val result = fixture.command.test("review show --threads closed --json")

          result.statusCode shouldBe 1
          fixture.stdout.toString().lineSequence().filter(String::isNotBlank).count() shouldBe 1
          fixture.stdout.toString() shouldContain "\"type\":\"failure\""
          fixture.stdout.toString() shouldContain "\"code\":\"INVALID_ARGUMENT\""
          fixture.stderr.toString().shouldBeEmpty()
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

private data class CommandCase(
    val name: String,
    val arguments: String,
    val expected: WorkflowCommandRequest,
)

private data class InvalidCommandCase(
    val name: String,
    val arguments: String,
)
