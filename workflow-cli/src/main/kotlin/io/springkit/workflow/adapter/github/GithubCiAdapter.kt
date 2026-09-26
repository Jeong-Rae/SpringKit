package io.springkit.workflow.adapter.github

import io.springkit.workflow.application.ChangeReceipt
import io.springkit.workflow.application.CiPort
import io.springkit.workflow.application.GetCiRequest
import io.springkit.workflow.application.GetCiResponse
import io.springkit.workflow.application.PortError
import io.springkit.workflow.application.PortResult
import io.springkit.workflow.application.StartCiRequest
import io.springkit.workflow.application.StartCiResponse
import io.springkit.workflow.common.CommandRunner
import io.springkit.workflow.common.LocalCommandRunner
import io.springkit.workflow.domain.CiRun
import io.springkit.workflow.domain.CiStatus
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** `gh pr view`의 statusCheckRollup을 Workflow CI 상태로 변환하는 응답입니다. */
@Serializable
data class GithubCiPullRequest(
    val headRefOid: String? = null,
    val statusCheckRollup: List<GithubStatusCheck>? = null,
)

@Serializable
data class GithubStatusCheck(
    val name: String = "",
    val context: String? = null,
    val status: String? = null,
    val state: String? = null,
    val conclusion: String? = null,
    val workflowName: String? = null,
    val detailsUrl: String? = null,
    val databaseId: Long? = null,
)

/** 설치된 `gh` 실행 파일에 pull request CI 조회를 위임하는 [CiPort] 구현입니다. */
class GithubCiAdapter(
    private val repositoryRoot: Path,
    private val commandRunner: CommandRunner = LocalCommandRunner(),
) : CiPort {
  override fun start(request: StartCiRequest): PortResult<StartCiResponse> {
    val response = get(GetCiRequest(request.pullRequestId, request.revision))
    if (response is PortResult.Failure) return response
    val result = response as PortResult.Success<GetCiResponse>
    val run =
        result.value.runs.firstOrNull()
            ?: CiRun(
                id = "github-${request.pullRequestId}",
                status = result.value.status,
                revision = request.revision,
                message = result.value.status.messageForEmptyRun(),
            )
    return PortResult.Success(
        StartCiResponse(
            run,
            ChangeReceipt("github-ci-start-${request.pullRequestId}", "github-ci-start"),
        )
    )
  }

  override fun get(request: GetCiRequest): PortResult<GetCiResponse> {
    val command =
        listOf(
            "gh",
            "pr",
            "view",
            request.pullRequestId,
            "--json",
            "headRefOid,statusCheckRollup",
        )
    val result =
        try {
          commandRunner.run(command, repositoryRoot)
        } catch (failure: Exception) {
          return PortResult.Failure(
              PortError(
                  code = "GITHUB_CI_FAILED",
                  message = "gh 명령을 실행할 수 없습니다: ${failure.message ?: failure::class.simpleName}",
                  retryable = true,
                  target = request.pullRequestId,
              )
          )
        }
    if (result.exitCode != 0) {
      return PortResult.Failure(
          PortError(
              code = "GITHUB_CI_FAILED",
              message =
                  result.stderr
                      .trim()
                      .ifBlank { result.stdout.trim() }
                      .ifBlank {
                        "gh 명령이 종료 코드 ${result.exitCode}로 실패했습니다."
                      },
              retryable = result.exitCode == 2,
              target = request.pullRequestId,
          )
      )
    }
    val payload =
        try {
          json.decodeFromString<GithubCiPullRequest>(result.stdout)
        } catch (_: SerializationException) {
          return PortResult.Failure(
              PortError(
                  code = "GITHUB_CI_RESPONSE_INVALID",
                  message = "GitHub CI 응답 JSON을 해석할 수 없습니다.",
                  target = request.pullRequestId,
              )
          )
        }
    if (request.revision != null && request.revision != payload.headRefOid) {
      return PortResult.Failure(
          PortError(
              code = "STALE_REVISION",
              message =
                  if (payload.headRefOid == null) {
                    "GitHub pull request revision을 확인할 수 없습니다."
                  } else {
                    "요청한 revision과 GitHub pull request revision이 다릅니다."
                  },
              target = request.pullRequestId,
          )
      )
    }
    val runs =
        payload.statusCheckRollup.orEmpty().mapIndexed { index, check ->
          check.toCiRun(index, payload.headRefOid)
        }
    return PortResult.Success(GetCiResponse(runs, runs.toCiStatus()))
  }

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }
  }
}

private fun GithubStatusCheck.toCiRun(index: Int, revision: String?): CiRun {
  val name = name.ifBlank { context ?: "check-$index" }
  val conclusionValue = conclusion.orEmpty().uppercase()
  val ciStatus = githubCheckStatus(status, state, conclusion)
  return CiRun(
      id = databaseId?.toString() ?: "github-check-$index",
      status = ciStatus,
      revision = revision,
      message =
          if (ciStatus == CiStatus.FAILED) conclusionValue.ifBlank { "GitHub check failed" }
          else null,
  )
}

private fun List<CiRun>.toCiStatus(): CiStatus =
    when {
      isEmpty() -> CiStatus.PENDING
      any { it.status == CiStatus.FAILED } -> CiStatus.FAILED
      any { it.status == CiStatus.RUNNING } -> CiStatus.RUNNING
      any { it.status == CiStatus.PENDING } -> CiStatus.PENDING
      else -> CiStatus.PASSED
    }

private fun CiStatus.messageForEmptyRun(): String? =
    if (this == CiStatus.FAILED) "GitHub CI가 실패했습니다." else null
