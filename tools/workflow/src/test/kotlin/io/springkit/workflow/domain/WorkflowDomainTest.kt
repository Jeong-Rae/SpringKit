package io.springkit.workflow.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class WorkflowDomainTest :
    FunSpec({
      val human = Actor("alice", ActorKind.HUMAN)
      val agent = Actor("agent", ActorKind.AGENT)

      context("열린 R thread를 확인할 때") {
        test("열린 R thread가 있으면, 차단 thread로 반환합니다") {
          val comment = ReviewComment("comment-1", agent, "fix this")
          val required = ReviewThread("thread-r", ReviewLevel.R, listOf(comment))
          val resolved =
              ReviewThread("thread-c", ReviewLevel.C, listOf(comment), ThreadState.RESOLVED)

          hasOpenRequiredThread(listOf(required, resolved)) shouldBe true
          WorkflowRules.openRequiredThreads(listOf(required, resolved)) shouldBe listOf(required)
        }

        test("열린 R thread가 없으면, 차단 thread가 없다고 판단합니다") {
          val comment = ReviewComment("comment-1", agent, "fix this")
          val resolved =
              ReviewThread("thread-c", ReviewLevel.C, listOf(comment), ThreadState.RESOLVED)

          hasOpenRequiredThread(listOf(resolved)) shouldBe false
        }
      }

      context("Approval이 현재 변경을 가리키는지 확인할 때") {
        test("활성 변경과 diff가 모두 일치하면, Approval을 적용합니다") {
          val change = ChangeRevision("cr-1", 1, Diff("diff-1"))
          val approval = Approval("approval-1", human, "cr-1", "diff-1")

          approvalApplies(approval, change) shouldBe true
        }

        test("diff가 다르면, Approval을 적용하지 않습니다") {
          val change = ChangeRevision("cr-1", 1, Diff("diff-1"))
          val approval = Approval("approval-1", human, "cr-1", "diff-1")

          approvalApplies(approval.copy(diffIdentity = "diff-2"), change) shouldBe false
        }

        test("변경이 비활성이면, Approval을 적용하지 않습니다") {
          val change = ChangeRevision("cr-1", 1, Diff("diff-1"))
          val approval = Approval("approval-1", human, "cr-1", "diff-1")

          approvalApplies(approval.copy(active = false), change) shouldBe false
        }

        test("actor가 agent이면, Approval 생성을 거부합니다") {
          shouldThrow<IllegalArgumentException> {
            Approval("approval-1", agent, "cr-1", "diff-1")
          }
        }
      }

      context("dependency cycle을 확인할 때") {
        test("순환 의존성이 있으면, 시작점으로 닫힌 경로를 반환합니다") {
          val cycle =
              dependencyCycle(
                  listOf(
                      Dependency("b", "a"),
                      Dependency("c", "b"),
                      Dependency("a", "c"),
                  ),
              )

          cycle shouldNotBe emptyList<String>()
          cycle.first() shouldBe cycle.last()
        }

        test("순환 의존성이 없으면, 순환 경로를 반환하지 않습니다") {
          hasDependencyCycle(listOf(Dependency("b", "a"))) shouldBe false
        }
      }

      context("human gate 조건을 확인할 때") {
        test("agent가 결정을 시도하면, HUMAN_REQUIRED 실패를 반환합니다") {
          val failure = humanGateCondition(GateType.READY, agent)

          failure.shouldNotBeNull().code shouldBe FailureCode.HUMAN_REQUIRED
        }
      }

      context("candidate risk를 계산할 때") {
        test("모든 subtask의 위험도가 NORMAL이면, NORMAL을 반환합니다") {
          candidateRisk(listOf(Risk.NORMAL, Risk.NORMAL)) shouldBe Risk.NORMAL
        }

        test("HIGH 위험도의 subtask가 하나라도 있으면, HIGH를 반환합니다") {
          candidateRisk(listOf(Risk.NORMAL, Risk.HIGH)) shouldBe Risk.HIGH
        }
      }

      context("release 후속 작업을 결정할 때") {
        test("공개 승인 대기 상태이면, 실행 가능한 Workflow CLI 명령을 반환합니다") {
          val release =
              Release(
                  id = "rel-1",
                  candidateId = "dc-1",
                  featureFlagId = "flag-v2",
                  state = ReleaseState.AWAITING_RELEASE_APPROVAL,
                  productionReady = true,
                  internalValidationPassed = true,
              )

          val action = nextReleaseAction(release).shouldNotBeNull()

          action.action shouldBe "approve_release"
          action.command shouldBe "./tools/workflow/bin/workflow gate release rel-1"
        }

        test("rollout이 해제된 release이면, cleanup subtask 생성 작업을 반환합니다") {
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

          val actual = action.shouldNotBeNull()

          actual.action shouldBe "create_cleanup_subtask"
          actual.command shouldBe null
        }

        test("release 상태가 RELEASED이면, 후속 작업을 반환하지 않습니다") {
          val release =
              Release(
                  id = "rel-1",
                  candidateId = "dc-1",
                  featureFlagId = "flag-v2",
                  state = ReleaseState.RELEASED,
                  productionReady = true,
                  internalValidationPassed = true,
              )

          nextReleaseAction(release) shouldBe null
        }
      }
    })
