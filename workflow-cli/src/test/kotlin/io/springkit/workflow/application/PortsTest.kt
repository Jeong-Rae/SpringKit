package io.springkit.workflow.application

import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortsTest {
  @Test
  fun `mutating port result exposes a compensatable change`() {
    val compensation =
        Compensation(
            id = "comp-1",
            operation = "remove-worktree",
            idempotencyKey = "request-1",
        )
    val receipt =
        ChangeReceipt(id = "change-1", operation = "create-worktree", compensation = compensation)
    val result = PortResult.Success(value = "workspace-1", change = receipt)

    assertNotNull(result.change)
    assertEquals("remove-worktree", result.change.compensation?.operation)
    assertEquals(ChangeStatus.APPLIED, result.change.status)
  }

  @Test
  fun `port contracts keep provider data behind domain requests and responses`() {
    val request = AuthorizeRequest(Actor("human-1", ActorKind.HUMAN), Capability.APPROVE, "sk-1")
    val response = AuthorizeResponse(allowed = true)

    assertEquals(Capability.APPROVE, request.capability)
    assertTrue(response.allowed)
  }

  @Test
  fun `transaction request carries an optimistic revision and idempotency key`() {
    val request =
        StoreTransactionRequest(
            transactionId = "tx-1",
            expectedRevision = "store-4",
            idempotencyKey = "request-1",
        )

    assertEquals("store-4", request.expectedRevision)
    assertEquals("request-1", request.idempotencyKey)
  }
}
