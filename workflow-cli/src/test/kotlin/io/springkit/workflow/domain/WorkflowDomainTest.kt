package io.springkit.workflow.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowDomainTest {
  private val human = Actor("alice", ActorKind.HUMAN)
  private val agent = Actor("agent", ActorKind.AGENT)

  @Test
  fun `open R threads are the only blocking threads`() {
    val comment = ReviewComment("comment-1", agent, "fix this")
    val required = ReviewThread("thread-r", ReviewLevel.R, listOf(comment))
    val resolved = ReviewThread("thread-c", ReviewLevel.C, listOf(comment), ThreadState.RESOLVED)

    assertTrue(hasOpenRequiredThread(listOf(required, resolved)))
    assertEquals(listOf(required), WorkflowRules.openRequiredThreads(listOf(required, resolved)))
    assertFalse(hasOpenRequiredThread(listOf(resolved)))
  }

  @Test
  fun `approval applies only to the exact active change and diff`() {
    val change = ChangeRevision("cr-1", 1, Diff("diff-1"))
    val approval = Approval("approval-1", human, "cr-1", "diff-1")

    assertTrue(approvalApplies(approval, change))
    assertFalse(approvalApplies(approval.copy(diffIdentity = "diff-2"), change))
    assertFalse(approvalApplies(approval.copy(active = false), change))
    assertFailsWith<IllegalArgumentException> { approval.copy(actor = agent) }
  }

  @Test
  fun `dependency cycle is reported as a closed path`() {
    val cycle =
        dependencyCycle(
            listOf(
                Dependency("b", "a"),
                Dependency("c", "b"),
                Dependency("a", "c"),
            ),
        )

    assertTrue(cycle.isNotEmpty())
    assertEquals(cycle.first(), cycle.last())
    assertFalse(hasDependencyCycle(listOf(Dependency("b", "a"))))
  }

  @Test
  fun `human gates reject agent decisions`() {
    val failure = humanGateCondition(GateType.READY, agent)

    assertNotNull(failure)
    assertEquals(FailureCode.HUMAN_REQUIRED, failure.code)
  }

  @Test
  fun `candidate risk is high when any included subtask is high`() {
    assertEquals(Risk.NORMAL, candidateRisk(listOf(Risk.NORMAL, Risk.NORMAL)))
    assertEquals(Risk.HIGH, candidateRisk(listOf(Risk.NORMAL, Risk.HIGH)))
  }

  @Test
  fun `release requests cleanup after rollout is released`() {
    val release =
        Release(
            id = "rel-1",
            candidateId = "dc-1",
            featureFlagId = "flag-v2",
            state = ReleaseState.CLEANUP_REQUIRED,
            productionReady = true,
            internalValidationPassed = true,
        )

    val action = nextReleaseAction(release)

    assertNotNull(action)
    assertEquals("create_cleanup_subtask", action.action)
    assertNull(action.command)
    assertNull(nextReleaseAction(release.copy(state = ReleaseState.RELEASED)))
  }
}
