package io.springkit.workflow.adapter.json

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.springkit.workflow.domain.ActorKind
import io.springkit.workflow.domain.BlockedBy
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.NextAction
import io.springkit.workflow.domain.WorkflowResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkflowResultJsonTest :
    FunSpec({
      context("WorkflowResult 실패 응답을 인코딩할 때") {
        test("공통 JSON 결과 계약을 사용하면, 차단 정보와 다음 작업을 data에 배치합니다") {
          val result =
              WorkflowResult.Failure(
                  FailureData(
                      code = FailureCode.HUMAN_REQUIRED,
                      message = "사람의 결정이 필요합니다.",
                      blockedBy = listOf(BlockedBy("HUMAN_REQUIRED", "사람만 실행할 수 있습니다.", "sk-1")),
                      next =
                          listOf(
                              NextAction(
                                  ActorKind.HUMAN,
                                  "approve_change",
                                  "workflow gate approve sk-1",
                              )
                          ),
                  ),
              )

          result.encodeToString() shouldBe
              "{\"data\":{\"blocked_by\":[{\"code\":\"HUMAN_REQUIRED\",\"message\":\"사람만 실행할 수 있습니다.\",\"target\":\"sk-1\"}],\"code\":\"HUMAN_REQUIRED\",\"message\":\"사람의 결정이 필요합니다.\",\"next\":[{\"action\":\"approve_change\",\"actor\":\"human\",\"command\":\"workflow gate approve sk-1\"}]},\"type\":\"failure\"}"
        }
      }

      context("WorkflowResult 성공 응답을 인코딩할 때") {
        test("다음 작업이 있으면, data 안에 다음 작업을 배치합니다") {
          val result =
              WorkflowResult.Success(
                  buildJsonObject { put("subtask", JsonPrimitive("sk-1")) },
                  listOf(NextAction(ActorKind.AGENT, "implement_change")),
              )

          result.encodeToString() shouldBe
              "{\"data\":{\"next\":[{\"action\":\"implement_change\",\"actor\":\"agent\"}],\"subtask\":\"sk-1\"},\"type\":\"success\"}"
        }
      }

      context("kotlinx Json으로 workflow 계약을 파싱할 때") {
        test("정상적인 JSON을 입력하면, 계약의 compact 표현을 유지합니다") {
          val value = Json.parseToJsonElement("{\"type\":\"success\",\"data\":{\"ok\":true}}")

          value.toString() shouldBe "{\"type\":\"success\",\"data\":{\"ok\":true}}"
        }
      }
    })
