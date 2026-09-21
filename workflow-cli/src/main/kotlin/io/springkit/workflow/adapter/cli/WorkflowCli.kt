package io.springkit.workflow.adapter.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.springkit.workflow.adapter.json.encodeToString
import io.springkit.workflow.domain.FailureCode
import io.springkit.workflow.domain.FailureData
import io.springkit.workflow.domain.WorkflowResult
import java.io.PrintStream
import kotlinx.serialization.json.JsonObject

/** CLI 어댑터가 이해하는 요청입니다. Application과 도메인 코드는 Clikt에 의존하지 않습니다. */
sealed interface WorkflowCommandRequest {
  data class Start(
      val task: String,
      val requestId: String,
      val title: String,
      val requires: String? = null,
  ) : WorkflowCommandRequest

  data object Check : WorkflowCommandRequest

  data class ReviewOpen(
      val bodyFile: String,
      val risk: String,
      val exposure: String,
      val featureFlag: String? = null,
  ) : WorkflowCommandRequest

  data class ReviewShow(
      val diff: Boolean = false,
      val threads: String = "open",
  ) : WorkflowCommandRequest

  data class ReviewUpdate(
      val revision: String,
      val bodyFile: String? = null,
  ) : WorkflowCommandRequest

  data class ReviewComment(
      val revision: String,
      val level: String,
      val body: String? = null,
      val bodyFile: String? = null,
      val path: String? = null,
      val line: Int? = null,
  ) : WorkflowCommandRequest

  data class ReviewReply(
      val revision: String,
      val thread: String,
      val body: String? = null,
      val bodyFile: String? = null,
  ) : WorkflowCommandRequest

  data class ReviewResolve(
      val revision: String,
      val thread: String,
  ) : WorkflowCommandRequest

  data class Stack(
      val requires: String? = null,
      val clear: Boolean = false,
  ) : WorkflowCommandRequest

  data class Sync(
      val continueSync: Boolean = false,
      val abort: Boolean = false,
  ) : WorkflowCommandRequest

  data class Status(
      val subtask: String? = null,
      val task: String? = null,
      val candidate: String? = null,
      val release: String? = null,
  ) : WorkflowCommandRequest

  data class GateReady(
      val target: String,
      val reviewRevision: String,
  ) : WorkflowCommandRequest

  data class GateApprove(
      val target: String,
      val changeRevision: String,
  ) : WorkflowCommandRequest

  data class GateDeploy(val target: String) : WorkflowCommandRequest

  data class GateRelease(val target: String) : WorkflowCommandRequest
}

/** 어댑터 사용자가 요청 모델을 간편하게 사용하도록 짧은 이름을 제공하며 Clikt 타입은 노출하지 않습니다. */
typealias StartCommandRequest = WorkflowCommandRequest.Start

typealias CheckCommandRequest = WorkflowCommandRequest.Check

typealias ReviewOpenCommandRequest = WorkflowCommandRequest.ReviewOpen

typealias ReviewShowCommandRequest = WorkflowCommandRequest.ReviewShow

typealias ReviewUpdateCommandRequest = WorkflowCommandRequest.ReviewUpdate

typealias ReviewCommentCommandRequest = WorkflowCommandRequest.ReviewComment

typealias ReviewReplyCommandRequest = WorkflowCommandRequest.ReviewReply

typealias ReviewResolveCommandRequest = WorkflowCommandRequest.ReviewResolve

typealias StackCommandRequest = WorkflowCommandRequest.Stack

typealias SyncCommandRequest = WorkflowCommandRequest.Sync

typealias StatusCommandRequest = WorkflowCommandRequest.Status

typealias GateReadyCommandRequest = WorkflowCommandRequest.GateReady

typealias GateApproveCommandRequest = WorkflowCommandRequest.GateApprove

typealias GateDeployCommandRequest = WorkflowCommandRequest.GateDeploy

typealias GateReleaseCommandRequest = WorkflowCommandRequest.GateRelease

/** CLI의 경계입니다. 런타임 조합에서 Application 기반 구현을 제공합니다. */
fun interface WorkflowCommandGateway {
  fun execute(request: WorkflowCommandRequest): WorkflowResult<JsonObject>
}

/** 공개 Workflow 명령 계약을 위한 Clikt 인바운드 어댑터입니다. */
class WorkflowCli(
    private val gateway: WorkflowCommandGateway,
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
) : CliktCommand(name = "workflow") {
  private var jsonOutputRequested = false

  init {
    configureContext {
      transformToken = { _, token ->
        if (token == "--json") jsonOutputRequested = true
        token
      }
      echoMessage = { context: Context, message: Any?, trailingNewline: Boolean, error: Boolean ->
        if (error && jsonOutputRequested) {
          stdout.println(
              WorkflowResult.Failure(
                      FailureData(
                          code = FailureCode.INVALID_ARGUMENT,
                          message = message.toString(),
                      )
                  )
                  .encodeToString()
          )
        } else {
          val output = if (error) stderr else stdout
          if (trailingNewline) output.println(message) else output.print(message)
        }
      }
    }
    subcommands(
        StartCommand(gateway, stdout, stderr),
        CheckCommand(gateway, stdout, stderr),
        ReviewCommand(gateway, stdout, stderr),
        StackCommand(gateway, stdout, stderr),
        SyncCommand(gateway, stdout, stderr),
        StatusCommand(gateway, stdout, stderr),
        GateCommand(gateway, stdout, stderr),
    )
  }

  override fun run() = Unit
}

private abstract class WorkflowLeafCommand(
    name: String,
    private val helpText: String,
    private val gateway: WorkflowCommandGateway,
    private val stdout: PrintStream,
    private val stderr: PrintStream,
) : CliktCommand(name = name) {
  override fun help(context: Context): String = helpText

  protected val json by option("--json", help = "구조화된 JSON 결과를 출력합니다.").flag()

  protected fun execute(request: WorkflowCommandRequest) {
    when (val result = gateway.execute(request)) {
      is WorkflowResult.Success -> {
        if (json) stdout.println(result.encodeToString())
        else stderr.println("workflow 작업이 완료되었습니다.")
      }
      is WorkflowResult.Failure -> {
        if (json) stdout.println(result.encodeToString())
        stderr.println("workflow 작업을 완료하지 못했습니다: ${result.data.message}")
        throw ProgramResult(1)
      }
    }
  }

  protected fun invalid(message: String): Nothing = currentContext.fail(message)

  protected fun requireExactlyOne(
      firstName: String,
      first: String?,
      secondName: String,
      second: String?,
  ): String {
    if ((first == null) == (second == null)) {
      invalid("$firstName 또는 $secondName 중 하나만 지정해야 합니다.")
    }
    return first ?: second!!
  }
}

private class StartCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("start", "새 SubTask와 Worktree를 시작합니다.", gateway, stdout, stderr) {
  private val task by option("--task", help = "외부 Task ID.").required()
  private val requestId by option("--request-id", help = "재시도를 식별하는 요청 ID.").required()
  private val title by option("--title", help = "SubTask 변경 의도.").required()
  private val requires by option("--requires", help = "직접 필요한 선행 SubTask ID.")

  override fun run() {
    execute(WorkflowCommandRequest.Start(task, requestId, title, requires))
  }
}

private class CheckCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("check", "현재 Worktree의 변경을 검증합니다.", gateway, stdout, stderr) {
  override fun run() = execute(WorkflowCommandRequest.Check)
}

private class ReviewCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : CliktCommand(name = "review") {
  init {
    subcommands(
        ReviewOpenCommand(gateway, stdout, stderr),
        ReviewShowCommand(gateway, stdout, stderr),
        ReviewUpdateCommand(gateway, stdout, stderr),
        ReviewCommentCommand(gateway, stdout, stderr),
        ReviewReplyCommand(gateway, stdout, stderr),
        ReviewResolveCommand(gateway, stdout, stderr),
    )
  }

  override fun help(context: Context): String = "PR Review lifecycle을 관리합니다."

  override fun run() = Unit
}

private class ReviewOpenCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("open", "현재 변경을 게시하고 Draft PR을 생성합니다.", gateway, stdout, stderr) {
  private val bodyFile by option("--body-file").required()
  private val risk by option("--risk").required()
  private val exposure by option("--exposure").required()
  private val featureFlag by option("--feature-flag")

  override fun run() {
    if (risk != "normal" && risk != "high") {
      invalid("--risk는 normal 또는 high여야 합니다.")
    }
    if (exposure != "unchanged" && exposure != "feature-flag") {
      invalid("--exposure는 unchanged 또는 feature-flag여야 합니다.")
    }
    if ((exposure == "feature-flag") != (featureFlag != null)) {
      invalid("feature-flag 공개에는 --feature-flag가 필요하고 unchanged 공개에는 사용할 수 없습니다.")
    }
    execute(WorkflowCommandRequest.ReviewOpen(bodyFile, risk, exposure, featureFlag))
  }
}

private class ReviewShowCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("show", "현재 Review 상태를 조회합니다.", gateway, stdout, stderr) {
  private val diff by option("--diff").flag()
  private val threads by option("--threads")

  override fun run() {
    val selectedThreads = threads ?: "open"
    if (selectedThreads != "open" && selectedThreads != "all") {
      invalid("--threads는 open 또는 all이어야 합니다.")
    }
    execute(WorkflowCommandRequest.ReviewShow(diff, selectedThreads))
  }
}

private class ReviewUpdateCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("update", "Review 변경을 다시 게시합니다.", gateway, stdout, stderr) {
  private val revision by option("--revision").required()
  private val bodyFile by option("--body-file")

  override fun run() {
    execute(WorkflowCommandRequest.ReviewUpdate(revision, bodyFile))
  }
}

private class ReviewCommentCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("comment", "Review 의견을 추가합니다.", gateway, stdout, stderr) {
  private val revision by option("--revision").required()
  private val level by option("--level").required()
  private val body by option("--body")
  private val bodyFile by option("--body-file")
  private val path by option("--path")
  private val line by option("--line").int()

  override fun run() {
    requireExactlyOne("--body", body, "--body-file", bodyFile)
    if ((path == null) != (line == null)) invalid("--path와 --line은 함께 지정해야 합니다.")
    if (level !in setOf("R", "C", "A")) invalid("--level은 R, C 또는 A여야 합니다.")
    execute(WorkflowCommandRequest.ReviewComment(revision, level, body, bodyFile, path, line))
  }
}

private class ReviewReplyCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("reply", "Review thread에 답변합니다.", gateway, stdout, stderr) {
  private val revision by option("--revision").required()
  private val thread by option("--thread").required()
  private val body by option("--body")
  private val bodyFile by option("--body-file")

  override fun run() {
    requireExactlyOne("--body", body, "--body-file", bodyFile)
    execute(WorkflowCommandRequest.ReviewReply(revision, thread, body, bodyFile))
  }
}

private class ReviewResolveCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("resolve", "Review thread를 해결 상태로 변경합니다.", gateway, stdout, stderr) {
  private val revision by option("--revision").required()
  private val thread by option("--thread").required()

  override fun run() {
    execute(WorkflowCommandRequest.ReviewResolve(revision, thread))
  }
}

private class StackCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("stack", "직접 코드 의존성을 변경합니다.", gateway, stdout, stderr) {
  private val requires by option("--requires")
  private val clear by option("--clear").flag()

  override fun run() {
    if ((requires == null) == !clear) invalid("--requires 또는 --clear 중 하나만 지정해야 합니다.")
    execute(WorkflowCommandRequest.Stack(requires, clear))
  }
}

private class SyncCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("sync", "Worktree와 원격 상태를 동기화합니다.", gateway, stdout, stderr) {
  private val continueSync by option("--continue").flag()
  private val abort by option("--abort").flag()

  override fun run() {
    if (continueSync && abort) invalid("--continue와 --abort 중 하나만 지정해야 합니다.")
    execute(WorkflowCommandRequest.Sync(continueSync, abort))
  }
}

private class StatusCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("status", "Workflow 상태와 다음 행동을 조회합니다.", gateway, stdout, stderr) {
  private val subtask by option("--subtask")
  private val task by option("--task")
  private val candidate by option("--candidate")
  private val release by option("--release")

  override fun run() {
    val selected = listOfNotNull(subtask, task, candidate, release)
    if (selected.size > 1) invalid("대상 선택 인자는 최대 하나만 사용할 수 있습니다.")
    execute(WorkflowCommandRequest.Status(subtask, task, candidate, release))
  }
}

private class GateCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : CliktCommand(name = "gate") {
  init {
    subcommands(
        GateReadyCommand(gateway, stdout, stderr),
        GateApproveCommand(gateway, stdout, stderr),
        GateDeployCommand(gateway, stdout, stderr),
        GateReleaseCommand(gateway, stdout, stderr),
    )
  }

  override fun help(context: Context): String = "사람의 Workflow 의사결정을 기록합니다."

  override fun run() = Unit
}

private class GateReadyCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("ready", "PR을 사람 리뷰 대상으로 전환합니다.", gateway, stdout, stderr) {
  private val target by argument("subtask")
  private val reviewRevision by option("--review-revision").required()

  override fun run() {
    execute(WorkflowCommandRequest.GateReady(target, reviewRevision))
  }
}

private class GateApproveCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("approve", "코드 변경을 승인합니다.", gateway, stdout, stderr) {
  private val target by argument("subtask")
  private val changeRevision by option("--change-revision").required()

  override fun run() {
    execute(WorkflowCommandRequest.GateApprove(target, changeRevision))
  }
}

private class GateDeployCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("deploy", "Production Canary 배포를 승인합니다.", gateway, stdout, stderr) {
  private val target by argument("candidate")

  override fun run() {
    execute(WorkflowCommandRequest.GateDeploy(target))
  }
}

private class GateReleaseCommand(
    gateway: WorkflowCommandGateway,
    stdout: PrintStream,
    stderr: PrintStream,
) : WorkflowLeafCommand("release", "외부 공개 시작을 승인합니다.", gateway, stdout, stderr) {
  private val target by argument("release")

  override fun run() {
    execute(WorkflowCommandRequest.GateRelease(target))
  }
}
