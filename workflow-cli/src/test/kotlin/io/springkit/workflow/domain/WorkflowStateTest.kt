package io.springkit.workflow.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowStateTest {
  @Test
  fun `subtask and review ids advance deterministically`() {
    val initial = WorkflowState()
    val (withSubTask, subTaskId) = initial.nextSubTaskId("sk")
    val (withReview, reviewId) = withSubTask.nextReviewRevision()
    val (_, changeId) = withReview.nextChangeRevision()

    assertEquals("sk-101", subTaskId)
    assertEquals("rv-1", reviewId)
    assertEquals("cr-1", changeId)
  }

  @Test
  fun `start request records must reference an existing subtask`() {
    val key = StartRequestKey("TASK-1", "request-1")
    val record = StartRequestRecord(key, "sk-101", "title")

    assertFailsWith<IllegalArgumentException> {
      WorkflowState(startRequests = mapOf(key to record))
    }
  }

  @Test
  fun `workflow state rejects dependency cycles`() {
    assertFailsWith<IllegalArgumentException> {
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
