package io.springkit.workflow.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorkflowStateTest :
    FunSpec({
      context("WorkflowState에서 식별자를 생성할 때") {
        test("subtask와 review를 차례로 생성하면, 식별자가 결정적으로 증가합니다") {
          val initial = WorkflowState()
          val (withSubTask, subTaskId) = initial.nextSubTaskId("sk")
          val (withReview, reviewId) = withSubTask.nextReviewRevision()
          val (_, changeId) = withReview.nextChangeRevision()

          subTaskId shouldBe "sk-101"
          reviewId shouldBe "rv-1"
          changeId shouldBe "cr-1"
        }
      }

      context("start request record의 subtask 참조를 검증할 때") {
        test("존재하지 않는 subtask를 참조하면, WorkflowState 생성을 거부합니다") {
          val key = StartRequestKey("TASK-1", "request-1")
          val record = StartRequestRecord(key, "sk-101", "title")

          shouldThrow<IllegalArgumentException> {
            WorkflowState(startRequests = mapOf(key to record))
          }
        }
      }

      context("WorkflowState의 dependency cycle을 검증할 때") {
        test("순환 의존성이 있으면, WorkflowState 생성을 거부합니다") {
          shouldThrow<IllegalArgumentException> {
            WorkflowState(
                subTasks =
                    listOf(
                            SubTask("sk-101", "TASK-1", "first", requires = "sk-102"),
                            SubTask("sk-102", "TASK-1", "second", requires = "sk-101"),
                        )
                        .associateBy { it.id },
            )
          }
        }
      }
    })
