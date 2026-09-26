package io.springkit.workflow.adapter.github

import io.springkit.workflow.domain.CiStatus

/** GitHub Check Run 또는 status context의 상태를 Workflow CI 상태로 변환합니다. */
internal fun githubCheckStatus(
    status: String?,
    state: String?,
    conclusion: String?,
): CiStatus {
  val statusValue = (status ?: state).orEmpty().uppercase()
  val conclusionValue = conclusion.orEmpty().uppercase()
  return when {
    conclusionValue == "SUCCESS" || conclusionValue == "NEUTRAL" || conclusionValue == "SKIPPED" ->
        CiStatus.PASSED
    conclusionValue.isNotBlank() -> CiStatus.FAILED
    statusValue == "SUCCESS" -> CiStatus.PASSED
    statusValue == "FAILURE" || statusValue == "ERROR" -> CiStatus.FAILED
    statusValue == "IN_PROGRESS" || statusValue == "EXPECTED" -> CiStatus.RUNNING
    else -> CiStatus.PENDING
  }
}
