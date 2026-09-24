package io.springkit.workflow.adapter.github

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.springkit.workflow.application.EnqueueMergeRequest
import io.springkit.workflow.application.EnqueueMergeResponse
import io.springkit.workflow.application.GetMergeQueueRequest
import io.springkit.workflow.application.GetMergeQueueResponse
import io.springkit.workflow.application.MergeQueueMergeRequest
import io.springkit.workflow.application.MergeQueueMergeResponse
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.common.CommandResult
import io.springkit.workflow.common.CommandRunner
import java.nio.file.Path

class GithubMergeQueueAdapterTest :
    FunSpec({
      context("GitHub pull request를 Merge Queue에 등록하면") {
        test("squash와 auto 토큰으로 gh pr merge를 실행하면, 요청 메타데이터를 보존합니다") {
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  ),
                  CommandResult(0, "queued", ""),
              )
          val adapter = GithubMergeQueueAdapter(Path.of("/repo"), runner)

          val result =
              adapter.enqueue(
                  EnqueueMergeRequest(
                      subTaskId = "sk-27",
                      pullRequestId = "17",
                      changeRevisionId = "cr-7",
                      expectedRevision = "store-4",
                  )
              )

          val response =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<EnqueueMergeResponse>()
          response.entry.subTaskId shouldBe "sk-27"
          response.entry.changeRevisionId shouldBe "cr-7"
          response.entry.providerRevision shouldBe "abc123"
          runner.commands shouldContainExactly
              listOf(
                  MergeQueueInvocation(
                      listOf(
                          "gh",
                          "pr",
                          "view",
                          "17",
                          "--json",
                          "number,state,isDraft,mergeStateStatus,reviewDecision,headRefName,headRefOid,statusCheckRollup,isInMergeQueue,autoMergeRequest,mergedAt,mergeCommit",
                      ),
                      Path.of("/repo"),
                  ),
                  MergeQueueInvocation(
                      listOf(
                          "gh",
                          "pr",
                          "merge",
                          "17",
                          "--squash",
                          "--auto",
                          "--match-head-commit",
                          "abc123",
                      ),
                      Path.of("/repo"),
                  ),
              )
        }
      }

      context("GitHub Merge Queue 상태를 조회하면") {
        test("gh JSON을 입력하면, 상태와 검증 결과를 MergeQueueEntry로 변환합니다") {
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """
                      {"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123","isInMergeQueue":true,"statusCheckRollup":[{"name":"build","status":"COMPLETED","conclusion":"SUCCESS","databaseId":1},{"name":"test","status":"COMPLETED","conclusion":"FAILURE","databaseId":2}]}
                      """
                          .trimIndent(),
                      "",
                  )
              )
          val adapter =
              GithubMergeQueueAdapter(
                  Path.of("/repo"),
                  runner,
                  GithubMergeQueueEntryResolver { _, fallback ->
                    fallback.copy(subTaskId = "sk-27", changeRevisionId = "cr-7")
                  },
              )

          val result = adapter.get(GetMergeQueueRequest(pullRequestId = "17"))

          val entries =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<GetMergeQueueResponse>()
                  .entries
          val entry = entries.single()
          entry.state.name shouldBe "FAILED"
          entry.subTaskId shouldBe "sk-27"
          entry.changeRevisionId shouldBe "cr-7"
          entry.validations.map { it.status.name } shouldBe listOf("PASSED", "FAILED")
          runner.commands.single().tokens shouldBe
              listOf(
                  "gh",
                  "pr",
                  "view",
                  "17",
                  "--json",
                  "number,state,isDraft,mergeStateStatus,reviewDecision,headRefName,headRefOid,statusCheckRollup,isInMergeQueue,autoMergeRequest,mergedAt,mergeCommit",
              )
        }
      }

      context("Merge Queue 항목을 squash merge하면") {
        test("gh 명령과 병합 JSON을 사용하면, 통합 revision을 반환합니다") {
          val persisted = mutableMapOf<String, io.springkit.workflow.domain.MergeQueueEntry>()
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  ),
                  CommandResult(0, "queued", ""),
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  ),
                  CommandResult(
                      0,
                      """{"state":"MERGED","headRefOid":"abc123","mergeCommit":{"oid":"def456"}}""",
                      "",
                  ),
                  CommandResult(
                      0,
                      """{"state":"MERGED","headRefOid":"abc123","mergeCommit":{"oid":"def456"}}""",
                      "",
                  ),
              )
          val adapter =
              GithubMergeQueueAdapter(
                  repositoryRoot = Path.of("/repo"),
                  commandRunner = runner,
                  entryLookup = { key -> persisted[key] },
                  entryPersister = { entry -> persisted[entry.id] = entry },
              )
          adapter.enqueue(EnqueueMergeRequest("sk-27", "17", "cr-7", "store-4"))

          val restartedAdapter =
              GithubMergeQueueAdapter(
                  repositoryRoot = Path.of("/repo"),
                  commandRunner = runner,
                  entryLookup = { key -> persisted[key] },
                  entryPersister = { entry -> persisted[entry.id] = entry },
              )
          val result =
              restartedAdapter.merge(MergeQueueMergeRequest("github-merge-queue-17", "cr-7"))

          val integration =
              result
                  .shouldBeTypeOf<PortResult.Success<*>>()
                  .value
                  .shouldBeTypeOf<MergeQueueMergeResponse>()
                  .integration
          integration.subTaskId shouldBe "sk-27"
          integration.mainRevision shouldBe "def456"
          integration.squashCommit shouldBe "def456"
          runner.commands.drop(2).map { it.tokens } shouldContainExactly
              listOf(
                  listOf(
                      "gh",
                      "pr",
                      "view",
                      "17",
                      "--json",
                      "number,state,isDraft,mergeStateStatus,reviewDecision,headRefName,headRefOid,statusCheckRollup,isInMergeQueue,autoMergeRequest,mergedAt,mergeCommit",
                  ),
                  listOf(
                      "gh",
                      "pr",
                      "merge",
                      "17",
                      "--squash",
                      "--match-head-commit",
                      "abc123",
                  ),
                  listOf(
                      "gh",
                      "pr",
                      "view",
                      "17",
                      "--json",
                      "number,state,isDraft,mergeStateStatus,reviewDecision,headRefName,headRefOid,statusCheckRollup,isInMergeQueue,autoMergeRequest,mergedAt,mergeCommit",
                  ),
              )
        }

        test("조회된 change revision이 다르면, squash merge를 실행하지 않습니다") {
          val persisted =
              io.springkit.workflow.domain.MergeQueueEntry(
                  id = "github-merge-queue-17",
                  subTaskId = "sk-27",
                  pullRequestId = "17",
                  changeRevisionId = "cr-1",
              )
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  )
              )
          val adapter =
              GithubMergeQueueAdapter(
                  Path.of("/repo"),
                  runner,
                  GithubMergeQueueEntryResolver { _, fallback ->
                    fallback.copy(subTaskId = "sk-27", changeRevisionId = "cr-1")
                  },
                  entryLookup = { persisted },
              )

          val result = adapter.merge(MergeQueueMergeRequest("github-merge-queue-17", "cr-2"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          runner.commands.size shouldBe 1
        }

        test("GitHub가 head OID 불일치로 merge를 거부하면, STALE_REVISION을 반환합니다") {
          val persisted =
              io.springkit.workflow.domain.MergeQueueEntry(
                  id = "github-merge-queue-17",
                  subTaskId = "sk-27",
                  pullRequestId = "17",
                  changeRevisionId = "cr-7",
              )
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  ),
                  CommandResult(1, "", "head commit does not match"),
              )
          val adapter =
              GithubMergeQueueAdapter(
                  repositoryRoot = Path.of("/repo"),
                  commandRunner = runner,
                  entryLookup = { persisted },
              )

          val result = adapter.merge(MergeQueueMergeRequest("github-merge-queue-17", "cr-7"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          runner.commands.size shouldBe 2
        }

        test("queue 등록 시점 이후 head OID가 바뀌면, STALE_REVISION을 반환합니다") {
          val persisted =
              io.springkit.workflow.domain.MergeQueueEntry(
                  id = "github-merge-queue-17",
                  subTaskId = "sk-27",
                  pullRequestId = "17",
                  changeRevisionId = "cr-7",
                  providerRevision = "old-oid",
              )
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"new-oid"}""",
                      "",
                  )
              )
          val adapter =
              GithubMergeQueueAdapter(
                  Path.of("/repo"),
                  commandRunner = runner,
                  entryLookup = { persisted },
              )

          val result = adapter.merge(MergeQueueMergeRequest("github-merge-queue-17", "cr-7"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "STALE_REVISION"
          runner.commands.size shouldBe 1
        }
      }

      context("gh 명령이 실패하면") {
        test("gh 명령이 종료 코드와 stderr를 반환하면, PortError로 변환하고 네트워크를 호출하지 않습니다") {
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  ),
                  CommandResult(1, "", "permission denied"),
              )
          val result =
              GithubMergeQueueAdapter(Path.of("/repo"), runner)
                  .enqueue(EnqueueMergeRequest("sk-27", "17", "cr-7", "store-4"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_MERGE_QUEUE_FAILED"
          failure.error.message shouldBe "permission denied"
          runner.commands.size shouldBe 2
        }

        test("CommandRunner가 예외를 던지면, 재시도 가능한 PortError로 변환합니다") {
          val runner = CommandRunner { _, _ -> error("gh unavailable") }
          val result =
              GithubMergeQueueAdapter(Path.of("/repo"), runner)
                  .enqueue(EnqueueMergeRequest("sk-27", "17", "cr-7", "store-4"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "GITHUB_MERGE_QUEUE_FAILED"
          failure.error.message shouldBe "gh 명령을 실행할 수 없습니다: gh unavailable"
          failure.error.retryable shouldBe true
        }
      }

      context("프로세스를 다시 시작한 뒤 Merge Queue를 병합하면") {
        test("영속 조회가 없으면, 인메모리 상태를 추측하지 않고 상태 필요 오류를 반환합니다") {
          val runner =
              FakeCommandRunner(
                  CommandResult(
                      0,
                      """{"number":17,"state":"OPEN","headRefName":"sk-27","headRefOid":"abc123"}""",
                      "",
                  )
              )

          val result =
              GithubMergeQueueAdapter(Path.of("/repo"), runner)
                  .merge(MergeQueueMergeRequest("github-merge-queue-17", "cr-7"))

          val failure = result.shouldBeTypeOf<PortResult.Failure>()
          failure.error.code shouldBe "MERGE_QUEUE_STATE_REQUIRED"
          runner.commands.size shouldBe 1
        }
      }
    })

private data class MergeQueueInvocation(val tokens: List<String>, val workingDirectory: Path)

private class FakeCommandRunner(vararg results: CommandResult) : CommandRunner {
  private val queuedResults = ArrayDeque(results.toList())
  val commands = mutableListOf<MergeQueueInvocation>()

  override fun run(command: List<String>, workingDirectory: Path): CommandResult {
    commands += MergeQueueInvocation(command, workingDirectory)
    return queuedResults.removeFirst()
  }
}
