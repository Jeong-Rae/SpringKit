package io.springkit.workflow.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.springkit.workflow.domain.Actor
import io.springkit.workflow.domain.ActorKind

class PortsTest :
    FunSpec({
      context("변경 결과를 확인할 때") {
        test("보상 정보가 있는 변경 결과를 생성하면, 변경과 보상 작업을 노출합니다") {
          val compensation =
              Compensation(
                  id = "comp-1",
                  operation = "remove-worktree",
                  idempotencyKey = "request-1",
              )
          val receipt =
              ChangeReceipt(
                  id = "change-1",
                  operation = "create-worktree",
                  compensation = compensation,
              )
          val result = PortResult.Success(value = "workspace-1", change = receipt)

          result.change.shouldNotBeNull().compensation?.operation shouldBe "remove-worktree"
          result.change.status shouldBe ChangeStatus.APPLIED
        }
      }

      context("포트 계약을 확인할 때") {
        test("인가 요청과 응답을 생성하면, 도메인 값과 허용 여부를 보존합니다") {
          val request =
              AuthorizeRequest(Actor("human-1", ActorKind.HUMAN), Capability.APPROVE, "sk-1")
          val response = AuthorizeResponse(allowed = true)

          request.capability shouldBe Capability.APPROVE
          response.allowed shouldBe true
        }
      }

      context("트랜잭션 요청을 확인할 때") {
        test("낙관적 리비전과 멱등성 키를 지정하면, 두 값을 요청에 보존합니다") {
          val request =
              StoreTransactionRequest(
                  transactionId = "tx-1",
                  expectedRevision = "store-4",
                  idempotencyKey = "request-1",
              )

          request.expectedRevision shouldBe "store-4"
          request.idempotencyKey shouldBe "request-1"
        }
      }

      context("워크플로 저장소 스냅샷을 확인할 때") {
        test("리비전만 지정하면, 복구 가능한 모든 상태 영역을 빈 값으로 보존합니다") {
          val snapshot = WorkflowStoreSnapshot(revision = "store-1")

          snapshot.checks shouldBe emptyMap()
          snapshot.mergeQueue shouldBe emptyList()
          snapshot.startRequests shouldBe emptyMap()
          snapshot.syncConflicts shouldBe emptyMap()
        }
      }
    })
