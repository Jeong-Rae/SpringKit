package io.springkit.workflow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.springkit.workflow.runtime.WorkflowConfigurationException
import io.springkit.workflow.runtime.createDefaultApplicationRuntime
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class MainTest :
    FunSpec({
      context("Native CLI 진입점이 실행 환경을 준비할 때") {
        test("구성 오류와 JSON을 함께 요청하면, 단일 failure JSON과 비정상 종료를 반환합니다") {
          val stdout = ByteArrayOutputStream()
          val stderr = ByteArrayOutputStream()
          val exit =
              shouldThrow<ExitSignal> {
                runWorkflow(
                    args = arrayOf("status", "--json"),
                    stdout = PrintStream(stdout),
                    stderr = PrintStream(stderr),
                    runtimeFactory = { throw WorkflowConfigurationException("provider 설정이 없습니다") },
                    exit = { throw ExitSignal(it) },
                )
              }

          exit.code shouldBe 1
          stdout.toString().lineSequence().filter(String::isNotBlank).count() shouldBe 1
          stdout.toString() shouldContain "\"type\":\"failure\""
          stdout.toString() shouldContain "\"code\":\"INVALID_ARGUMENT\""
          stderr.toString() shouldContain "provider 설정이 없습니다"
        }

        test("구성 오류를 일반 모드로 요청하면, JSON 없이 사람이 읽을 오류와 비정상 종료를 반환합니다") {
          val stdout = ByteArrayOutputStream()
          val stderr = ByteArrayOutputStream()
          val exit =
              shouldThrow<ExitSignal> {
                runWorkflow(
                    args = arrayOf("status"),
                    stdout = PrintStream(stdout),
                    stderr = PrintStream(stderr),
                    runtimeFactory = { throw WorkflowConfigurationException("provider 설정이 없습니다") },
                    exit = { throw ExitSignal(it) },
                )
              }

          exit.code shouldBe 1
          stdout.toString().shouldBeEmpty()
          stderr.toString() shouldContain "workflow 실행 환경 설정을 확인하지 못했습니다"
          stderr.toString() shouldContain "provider 설정이 없습니다"
        }

        test("예상하지 못한 초기화 오류이면, 오류를 숨기지 않고 전파합니다") {
          val unexpected = IllegalStateException("programming error")

          shouldThrow<IllegalStateException> {
            runWorkflow(
                args = arrayOf("status"),
                stdout = PrintStream(ByteArrayOutputStream()),
                stderr = PrintStream(ByteArrayOutputStream()),
                runtimeFactory = { throw unexpected },
                exit = { throw ExitSignal(it) },
            )
          } shouldBe unexpected
        }

        test("허용되지 않은 WORKFLOW_ACTOR_KIND는 구성 오류로 거부합니다") {
          shouldThrow<WorkflowConfigurationException> {
            createDefaultApplicationRuntime(
                currentDirectory = Path.of("."),
                environment = mapOf("WORKFLOW_ACTOR_KIND" to "HUMAN"),
            )
          }
        }

        test("빈 WORKFLOW_ACTOR_KIND는 미설정 기본값으로 처리합니다") {
          createDefaultApplicationRuntime(
              currentDirectory = Path.of("."),
              environment =
                  mapOf(
                      "WORKFLOW_ACTOR_KIND" to "  ",
                      "WORKFLOW_REPO_ROOT" to ".",
                  ),
          )
        }

        test("잘못된 저장소 경로는 구성 오류로 거부합니다") {
          shouldThrow<WorkflowConfigurationException> {
            createDefaultApplicationRuntime(
                currentDirectory = Path.of("."),
                environment = mapOf("WORKFLOW_REPO_ROOT" to "\u0000"),
            )
          }
        }
      }
    })

private class ExitSignal(val code: Int) : RuntimeException()
