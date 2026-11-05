package io.springkit.workflow.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ValidationStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
  SKIPPED,
}

@Serializable
data class Validation(
    val id: ValidationId,
    val name: String,
    val status: ValidationStatus,
    val required: Boolean = true,
    val revision: String? = null,
    val message: String? = null,
) {
  init {
    require(id.isNotBlank()) { "validation id must not be blank" }
    require(name.isNotBlank()) { "validation name must not be blank" }
    require(status != ValidationStatus.FAILED || !message.isNullOrBlank()) {
      "failed validation must have a message"
    }
  }

  val passed: Boolean
    get() = status == ValidationStatus.PASSED
}

@Serializable
data class CheckResult(
    val id: CheckId,
    val name: String,
    val status: ValidationStatus,
    val fingerprint: String,
    val revision: String,
    val message: String? = null,
) {
  init {
    require(id.isNotBlank()) { "check id must not be blank" }
    require(name.isNotBlank()) { "check name must not be blank" }
    require(fingerprint.isNotBlank()) { "check fingerprint must not be blank" }
    require(revision.isNotBlank()) { "check revision must not be blank" }
    require(status != ValidationStatus.FAILED || !message.isNullOrBlank()) {
      "failed check must have a message"
    }
  }
}

@Serializable
data class CheckSummary(
    val fingerprint: String,
    val revision: String,
    val checks: List<CheckResult>,
) {
  init {
    require(fingerprint.isNotBlank()) { "check summary fingerprint must not be blank" }
    require(revision.isNotBlank()) { "check summary revision must not be blank" }
    require(checks.map { it.id }.distinct().size == checks.size) { "check ids must be unique" }
  }

  val passed: Boolean
    get() = checks.isNotEmpty() && checks.all { it.status == ValidationStatus.PASSED }

  fun appliesTo(currentFingerprint: String, currentRevision: String): Boolean =
      fingerprint == currentFingerprint && revision == currentRevision
}

data class CiRun(
    val id: String,
    val status: CiStatus,
    val required: Boolean = true,
    val revision: String? = null,
    val message: String? = null,
) {
  init {
    require(id.isNotBlank()) { "ci run id must not be blank" }
    require(status != CiStatus.FAILED || !message.isNullOrBlank()) {
      "failed CI must have a message"
    }
  }
}

fun requiredValidationsPassed(validations: Iterable<Validation>): Boolean =
    validations.filter { it.required }.all { it.status == ValidationStatus.PASSED }

fun checksPassedFor(checks: Iterable<CheckResult>, fingerprint: String, revision: String): Boolean =
    checks.none {
      it.fingerprint == fingerprint &&
          it.revision == revision &&
          it.status != ValidationStatus.PASSED
    } && checks.any { it.fingerprint == fingerprint && it.revision == revision }
