#!/usr/bin/env kotlin

import java.io.File
import kotlin.system.exitProcess

data class CommandResult(val exitCode: Int, val output: String)

data class PullRequestStatus(
    val branch: String,
    val isDraft: Boolean,
    val url: String,
)

data class FinishPullRequest(
    val state: String,
    val baseBranch: String,
    val isDraft: Boolean,
    val headCommit: String,
    val mergeCommit: String,
    val url: String,
)

data class GitWorktree(
    val directory: File,
    val branch: String?,
)

enum class FinishPhase {
    DISCOVER,
    PREFLIGHT,
    MERGE,
    RECONCILE,
    SYNC_DEVELOP,
    BUILD,
    CLEANUP,
    VERIFY,
    SUCCEEDED,
}

enum class WorkflowResult {
    FAILED,
    RECOVERY_REQUIRED,
    SUCCEEDED,
}

data class WorkflowPayload(
    val result: WorkflowResult,
    val command: String,
    val phase: String,
    val message: String,
    val recovery: String? = null,
) {
    fun render(): String = buildList {
        add("result: $result")
        add("command: $command")
        add("phase: $phase")
        add("message: $message")
        recovery?.let { add("recovery: $it") }
    }.joinToString("\n")
}

class WorkflowException(val payload: WorkflowPayload) : RuntimeException(payload.message)

class FinishStateMachine(private val taskId: String) {
    var phase = FinishPhase.DISCOVER
        private set
    private var remoteMerged = false
    private var mergeOutcomeUnknown = false

    fun transition(next: FinishPhase) {
        val allowed = when (phase) {
            FinishPhase.DISCOVER -> setOf(FinishPhase.PREFLIGHT)
            FinishPhase.PREFLIGHT -> setOf(FinishPhase.MERGE, FinishPhase.RECONCILE)
            FinishPhase.MERGE -> setOf(FinishPhase.RECONCILE)
            FinishPhase.RECONCILE -> setOf(FinishPhase.SYNC_DEVELOP)
            FinishPhase.SYNC_DEVELOP -> setOf(FinishPhase.BUILD)
            FinishPhase.BUILD -> setOf(FinishPhase.CLEANUP)
            FinishPhase.CLEANUP -> setOf(FinishPhase.VERIFY)
            FinishPhase.VERIFY -> setOf(FinishPhase.SUCCEEDED)
            FinishPhase.SUCCEEDED -> emptySet()
        }
        if (next !in allowed) {
            fail("허용되지 않은 상태 전이입니다: $phase -> $next")
        }
        phase = next
    }

    fun markMergeAttempted() {
        mergeOutcomeUnknown = true
    }

    fun markMergeNotApplied() {
        mergeOutcomeUnknown = false
    }

    fun markRemoteMerged() {
        remoteMerged = true
        mergeOutcomeUnknown = false
    }

    fun fail(message: String, recovery: String? = null): Nothing {
        val result = if (remoteMerged || mergeOutcomeUnknown) {
            WorkflowResult.RECOVERY_REQUIRED
        } else {
            WorkflowResult.FAILED
        }
        throw WorkflowException(
            WorkflowPayload(result, "finish $taskId", phase.name, message, recovery),
        )
    }

    fun wrap(exception: Exception): Nothing {
        if (exception is WorkflowException) throw exception
        fail(exception.message ?: exception::class.simpleName.orEmpty())
    }
}

fun execute(
    workingDirectory: File,
    vararg command: String,
    captureOutput: Boolean = false,
    check: Boolean = true,
): CommandResult {
    val process = ProcessBuilder(*command)
        .directory(workingDirectory)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    if (check && exitCode != 0) {
        val detail = output.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        error("명령 실행에 실패했습니다 (${command.joinToString(" ")}).$detail")
    }
    if (!captureOutput && output.isNotBlank()) {
        println(output.trimEnd())
    }
    return CommandResult(exitCode, output.trim())
}

fun commandOutput(workingDirectory: File, vararg command: String): String =
    execute(workingDirectory, *command, captureOutput = true).output

val invocationDirectory = File(System.getProperty("user.dir"))
val repositoryRoot: File by lazy {
    File(commandOutput(invocationDirectory, "git", "rev-parse", "--show-toplevel"))
}

fun requireCommand(name: String) {
    val result = execute(
        repositoryRoot,
        "bash",
        "-lc",
        "command -v \"\$1\" >/dev/null 2>&1",
        "git-workflow",
        name,
        captureOutput = true,
        check = false,
    )
    require(result.exitCode == 0) { "필수 명령을 찾을 수 없습니다: $name" }
}

fun requireTaskId(taskId: String) {
    require(taskId.isNotBlank()) { "Task 식별자는 비어 있을 수 없습니다." }
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(taskId)) {
        "Task 식별자는 Git 브랜치 이름에 안전한 문자만 사용할 수 있습니다: $taskId"
    }
}

fun currentBranch(): String = commandOutput(repositoryRoot, "git", "branch", "--show-current")

fun gitWorktrees(): List<GitWorktree> =
    commandOutput(repositoryRoot, "git", "worktree", "list", "--porcelain")
        .split("\n\n")
        .filter { it.isNotBlank() }
        .map { record ->
            val fields = record.lineSequence().associate { line ->
                val parts = line.split(' ', limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
            val directory = fields["worktree"]
            require(!directory.isNullOrBlank()) { "Git worktree 경로를 해석할 수 없습니다.\n$record" }
            GitWorktree(
                File(directory),
                fields["branch"]?.removePrefix("refs/heads/"),
            )
        }

fun worktreeForBranch(branch: String): GitWorktree? =
    gitWorktrees().singleOrNull { it.branch == branch }

fun requireDevelop() {
    require(currentBranch() == "develop") { "현재 브랜치가 develop이어야 합니다." }
}

fun requireCleanTrackedFiles(workingDirectory: File = repositoryRoot) {
    val changed = commandOutput(workingDirectory, "git", "status", "--short", "--untracked-files=no")
    require(changed.isBlank()) { "추적 중인 파일에 커밋되지 않은 변경이 있습니다. 먼저 정리하거나 커밋하세요.\n$changed" }
}

fun branchExists(branch: String): Boolean =
    execute(
        repositoryRoot,
        "git",
        "show-ref",
        "--verify",
        "--quiet",
        "refs/heads/$branch",
        captureOutput = true,
        check = false,
    ).exitCode == 0

fun remoteBranchCommit(branch: String): String? =
    commandOutput(repositoryRoot, "git", "ls-remote", "--heads", "origin", branch)
        .takeIf { it.isNotBlank() }
        ?.substringBefore('\t')

fun remoteBranchExists(branch: String): Boolean = remoteBranchCommit(branch) != null

fun isAncestor(ancestor: String, descendant: String): Boolean =
    execute(
        repositoryRoot,
        "git",
        "merge-base",
        "--is-ancestor",
        ancestor,
        descendant,
        captureOutput = true,
        check = false,
    ).exitCode == 0

fun start(taskId: String) {
    requireTaskId(taskId)
    requireCommand("git-flow")
    requireDevelop()
    requireCleanTrackedFiles()

    execute(repositoryRoot, "git", "fetch", "origin", "develop")
    val localDevelop = commandOutput(repositoryRoot, "git", "rev-parse", "develop")
    val remoteDevelop = commandOutput(repositoryRoot, "git", "rev-parse", "origin/develop")
    require(localDevelop == remoteDevelop) {
        "로컬 develop이 origin/develop과 일치하지 않습니다. pull과 build를 완료한 뒤 다시 실행하세요."
    }

    execute(repositoryRoot, "git", "flow", "feature", "start", taskId)
}

fun publish(title: String, bodyFileArgument: String) {
    requireCommand("git-flow")
    requireCommand("gh")
    requireCleanTrackedFiles()

    val branch = currentBranch()
    require(branch.startsWith("feature/") && branch.length > "feature/".length) {
        "publish는 feature/<task-id> 브랜치에서만 실행할 수 있습니다."
    }

    val taskId = branch.removePrefix("feature/")
    requireTaskId(taskId)
    val titlePrefix = "[$taskId] Feature: "
    require(title.startsWith(titlePrefix) && title.removePrefix(titlePrefix).isNotBlank()) {
        "PR 제목은 '$titlePrefix<description>' 형식이어야 합니다."
    }

    val bodyFile = File(bodyFileArgument).let { if (it.isAbsolute) it else File(invocationDirectory, bodyFileArgument) }
    require(bodyFile.isFile) { "PR 본문 파일을 찾을 수 없습니다: ${bodyFile.path}" }
    require(bodyFile.readText().isNotBlank()) { "PR 본문 파일은 비어 있을 수 없습니다: ${bodyFile.path}" }

    val existingPrUrl = commandOutput(
        repositoryRoot,
        "gh",
        "pr",
        "list",
        "--head",
        branch,
        "--state",
        "all",
        "--json",
        "url",
        "--jq",
        ".[0].url // \"\"",
    )
    require(existingPrUrl.isBlank()) { "이미 이 브랜치의 PR이 존재합니다: $existingPrUrl" }

    execute(repositoryRoot, "git", "flow", "feature", "publish", taskId)

    execute(
        repositoryRoot,
        "gh",
        "pr",
        "create",
        "--draft",
        "--base",
        "develop",
        "--head",
        branch,
        "--title",
        title,
        "--body-file",
        bodyFile.absolutePath,
    )
}

fun update() {
    requireCommand("git-flow")
    requireCommand("gh")
    requireCleanTrackedFiles()

    val branch = currentBranch()
    require(branch.startsWith("feature/") && branch.length > "feature/".length) {
        "update는 feature/<task-id> 브랜치에서만 실행할 수 있습니다."
    }

    val taskId = branch.removePrefix("feature/")
    requireTaskId(taskId)
    val pr = commandOutput(
        repositoryRoot,
        "gh",
        "pr",
        "view",
        branch,
        "--json",
        "state,baseRefName,url",
        "--jq",
        "[.state, .baseRefName, .url] | @tsv",
    ).split('\t')

    require(pr.size == 3) { "PR 상태 응답을 해석할 수 없습니다." }
    val (state, baseBranch, prUrl) = pr
    require(state == "OPEN") { "리뷰 반영 대상 PR이 OPEN 상태가 아닙니다: $prUrl ($state)" }
    require(baseBranch == "develop") { "PR 대상 브랜치가 develop이 아닙니다: $baseBranch" }

    execute(repositoryRoot, "git", "flow", "feature", "publish", taskId)
}

fun currentFeaturePullRequest(commandName: String): PullRequestStatus {
    val branch = currentBranch()
    require(branch.startsWith("feature/") && branch.length > "feature/".length) {
        "$commandName 명령은 feature/<task-id> 브랜치에서만 실행할 수 있습니다."
    }

    val pr = commandOutput(
        repositoryRoot,
        "gh",
        "pr",
        "view",
        branch,
        "--json",
        "state,baseRefName,isDraft,url",
        "--jq",
        "[.state, .baseRefName, (.isDraft | tostring), .url] | @tsv",
    ).split('\t')

    require(pr.size == 4) { "PR 상태 응답을 해석할 수 없습니다." }
    val (state, baseBranch, isDraft, prUrl) = pr
    require(state == "OPEN") { "PR이 OPEN 상태가 아닙니다: $prUrl ($state)" }
    require(baseBranch == "develop") { "PR 대상 브랜치가 develop이 아닙니다: $baseBranch" }
    require(isDraft == "true" || isDraft == "false") { "PR 초안 상태를 해석할 수 없습니다: $isDraft" }

    return PullRequestStatus(branch, isDraft.toBoolean(), prUrl)
}

fun markReady() {
    requireCommand("gh")
    requireCleanTrackedFiles()

    val pr = currentFeaturePullRequest("ready")
    require(pr.isDraft) { "PR이 이미 초안 해제 상태입니다: ${pr.url}" }

    execute(repositoryRoot, "gh", "pr", "ready", pr.branch)
}

fun markDraft() {
    requireCommand("gh")
    requireCleanTrackedFiles()

    val pr = currentFeaturePullRequest("draft")
    require(!pr.isDraft) { "PR이 이미 초안 상태입니다: ${pr.url}" }

    execute(repositoryRoot, "gh", "pr", "ready", "--undo", pr.branch)
}

fun readFinishPullRequest(pullRequestUrl: String): FinishPullRequest {
    val fields = commandOutput(
        repositoryRoot,
        "gh",
        "pr",
        "view",
        pullRequestUrl,
        "--json",
        "state,baseRefName,isDraft,headRefOid,mergeCommit,url",
        "--jq",
        "[.state, .baseRefName, (.isDraft | tostring), .headRefOid, (.mergeCommit.oid // \"\"), .url] | @tsv",
    ).split('\t')
    require(fields.size == 6) { "PR 상태 응답을 해석할 수 없습니다." }
    require(fields[2] == "true" || fields[2] == "false") {
        "PR 초안 상태를 해석할 수 없습니다: ${fields[2]}"
    }
    return FinishPullRequest(
        state = fields[0],
        baseBranch = fields[1],
        isDraft = fields[2].toBoolean(),
        headCommit = fields[3],
        mergeCommit = fields[4],
        url = fields[5],
    )
}

fun finish(taskId: String) {
    val machine = FinishStateMachine(taskId)
    try {
        requireTaskId(taskId)
        requireCommand("gh")

        val branch = "feature/$taskId"
        val pullRequestUrl = commandOutput(
            repositoryRoot,
            "gh",
            "pr",
            "list",
            "--head",
            branch,
            "--state",
            "all",
            "--limit",
            "1",
            "--json",
            "url",
            "--jq",
            ".[0].url // \"\"",
        )
        if (pullRequestUrl.isBlank()) {
            machine.fail("feature의 PR을 찾을 수 없습니다: $branch")
        }

        var pr = readFinishPullRequest(pullRequestUrl)
        if (pr.baseBranch != "develop") {
            machine.fail("PR 대상 브랜치가 develop이 아닙니다: ${pr.baseBranch}")
        }
        if (pr.state != "OPEN" && pr.state != "MERGED") {
            machine.fail("병합할 수 없는 PR 상태입니다: ${pr.url} (${pr.state})")
        }
        if (pr.state == "MERGED") {
            machine.markRemoteMerged()
        }

        machine.transition(FinishPhase.PREFLIGHT)
        requireCleanTrackedFiles()
        val developWorktree = worktreeForBranch("develop")
        val developDirectory = developWorktree?.directory ?: repositoryRoot
        if (developWorktree != null) {
            requireCleanTrackedFiles(developDirectory)
        }
        worktreeForBranch(branch)?.directory?.let(::requireCleanTrackedFiles)

        val gradleWrapper = File(developDirectory, "gradlew")
        if (!gradleWrapper.isFile) {
            machine.fail("Gradle wrapper를 찾을 수 없습니다: ${gradleWrapper.path}")
        }

        val localFeature = if (branchExists(branch)) {
            commandOutput(repositoryRoot, "git", "rev-parse", branch)
        } else {
            null
        }
        val remoteFeature = remoteBranchCommit(branch)
        if (pr.state == "OPEN") {
            if (localFeature == null) {
                machine.fail("병합할 로컬 feature 브랜치를 찾을 수 없습니다: $branch")
            }
            if (pr.isDraft) {
                machine.fail("초안 PR은 병합할 수 없습니다. ready 명령을 먼저 실행하세요: ${pr.url}")
            }
            if (localFeature != pr.headCommit || remoteFeature != pr.headCommit) {
                machine.fail(
                    "로컬과 원격 $branch 가 PR HEAD와 일치하지 않습니다.",
                    "PR이 OPEN 상태일 때 update를 실행한 뒤 다시 시도하세요.",
                )
            }
        } else {
            if (localFeature != null && localFeature != pr.headCommit) {
                machine.fail(
                    "병합된 PR에 포함되지 않은 로컬 커밋이 있어 $branch 를 정리할 수 없습니다.",
                    "추가 커밋을 새 Task 브랜치로 옮긴 뒤 finish를 다시 실행하세요.",
                )
            }
            if (remoteFeature != null && remoteFeature != pr.headCommit) {
                machine.fail(
                    "병합된 PR에 포함되지 않은 원격 커밋이 있어 $branch 를 정리할 수 없습니다.",
                    "추가 커밋을 새 Task 브랜치로 보존한 뒤 finish를 다시 실행하세요.",
                )
            }
        }

        if (pr.state == "OPEN") {
            machine.transition(FinishPhase.MERGE)
            machine.markMergeAttempted()
            val mergeResult = execute(
                repositoryRoot,
                "gh",
                "pr",
                "merge",
                pr.url,
                "--squash",
                "--match-head-commit",
                pr.headCommit,
                captureOutput = true,
                check = false,
            )
            pr = readFinishPullRequest(pr.url)
            if (pr.state != "MERGED") {
                machine.markMergeNotApplied()
                val detail = mergeResult.output.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                machine.fail("PR 병합이 완료되지 않았습니다: ${pr.url} (${pr.state}).$detail")
            }
            machine.markRemoteMerged()
            machine.transition(FinishPhase.RECONCILE)
        } else {
            machine.transition(FinishPhase.RECONCILE)
        }

        if (pr.baseBranch != "develop") {
            machine.fail("PR 대상 브랜치가 develop이 아닙니다: ${pr.baseBranch}")
        }
        if (pr.mergeCommit.isBlank()) {
            machine.fail("PR의 squash commit을 확인할 수 없습니다: ${pr.url}")
        }

        machine.transition(FinishPhase.SYNC_DEVELOP)
        if (developWorktree == null) {
            execute(repositoryRoot, "git", "switch", "develop")
        }
        execute(developDirectory, "git", "pull", "--ff-only", "origin", "develop")
        if (!isAncestor(pr.mergeCommit, "develop")) {
            machine.fail("원격 PR의 squash commit이 로컬 develop에 포함되지 않았습니다: ${pr.mergeCommit}")
        }
        val mergeCommitFields = commandOutput(
            repositoryRoot,
            "git",
            "rev-list",
            "--parents",
            "-n",
            "1",
            pr.mergeCommit,
        ).split(' ')
        if (mergeCommitFields.size != 2) {
            machine.fail("PR이 squash 방식으로 병합되지 않았습니다: ${pr.url}")
        }

        machine.transition(FinishPhase.BUILD)
        execute(developDirectory, gradleWrapper.absolutePath, "build")

        machine.transition(FinishPhase.CLEANUP)
        remoteBranchCommit(branch)?.let { remoteCommit ->
            if (remoteCommit != pr.headCommit) {
                machine.fail(
                    "병합 후 원격 $branch 에 새 커밋이 추가되어 삭제하지 않습니다.",
                    "추가 커밋을 새 Task 브랜치로 보존한 뒤 finish를 다시 실행하세요.",
                )
            }
            val deleteResult = execute(
                repositoryRoot,
                "git",
                "push",
                "origin",
                "--delete",
                branch,
                captureOutput = true,
                check = false,
            )
            if (remoteBranchExists(branch)) {
                machine.fail("원격 $branch 삭제에 실패했습니다. ${deleteResult.output}")
            }
        }
        if (branchExists(branch)) {
            val currentLocalFeature = commandOutput(repositoryRoot, "git", "rev-parse", branch)
            if (currentLocalFeature != pr.headCommit) {
                machine.fail(
                    "병합 후 로컬 $branch 에 새 커밋이 추가되어 삭제하지 않습니다.",
                    "추가 커밋을 새 Task 브랜치로 옮긴 뒤 finish를 다시 실행하세요.",
                )
            }
            worktreeForBranch(branch)?.let { featureWorktree ->
                execute(featureWorktree.directory, "git", "switch", "--detach", "develop")
            }
            execute(developDirectory, "git", "branch", "-D", branch)
        }

        machine.transition(FinishPhase.VERIFY)
        if (!isAncestor(pr.mergeCommit, "develop")) {
            machine.fail("완료 검증에서 develop의 병합 커밋을 확인하지 못했습니다: ${pr.mergeCommit}")
        }
        if (remoteBranchExists(branch) || branchExists(branch)) {
            machine.fail("완료 검증에서 feature 브랜치가 남아 있습니다: $branch")
        }
        requireCleanTrackedFiles(developDirectory)

        machine.transition(FinishPhase.SUCCEEDED)
        println(
            WorkflowPayload(
                WorkflowResult.SUCCEEDED,
                "finish $taskId",
                machine.phase.name,
                "PR 병합과 로컬 정리를 모두 완료했습니다: ${pr.url}",
            ).render(),
        )
    } catch (exception: Exception) {
        machine.wrap(exception)
    }
}

data class InteractiveCommand(
    val name: String,
    val description: String,
)

val interactiveCommands = listOf(
    InteractiveCommand("start", "새 기능 브랜치 시작"),
    InteractiveCommand("publish", "기능 브랜치 게시 및 초안 PR 생성"),
    InteractiveCommand("update", "리뷰 반영 커밋 게시"),
    InteractiveCommand("ready", "PR을 리뷰 준비 상태로 전환"),
    InteractiveCommand("draft", "PR을 초안 상태로 전환"),
    InteractiveCommand("finish", "PR 스쿼시 병합 및 기능 브랜치 정리"),
    InteractiveCommand("exit", "종료"),
)

fun prompt(question: String): String {
    print("? $question ")
    System.out.flush()
    val answer = readlnOrNull()?.trim()
    require(!answer.isNullOrBlank()) { "입력이 필요합니다: $question" }
    return answer
}

fun chooseInteractiveCommand(): String {
    println("? 실행할 작업을 선택하세요")
    interactiveCommands.forEachIndexed { index, command ->
        println("  ${index + 1}) ${command.name} - ${command.description}")
    }

    while (true) {
        val selection = prompt("선택")
        val selectedByNumber = selection.toIntOrNull()?.let { interactiveCommands.getOrNull(it - 1) }
        val selectedByName = interactiveCommands.firstOrNull { command -> command.name == selection.lowercase() }
        val selected = selectedByNumber ?: selectedByName
        if (selected != null) {
            return selected.name
        }
        println("! 번호 또는 명령 이름을 입력하세요.")
    }
}

fun interactive() {
    when (chooseInteractiveCommand()) {
        "start" -> start(prompt("Task 식별자"))
        "publish" -> publish(prompt("PR 제목"), prompt("PR 본문 파일 경로"))
        "update" -> update()
        "ready" -> markReady()
        "draft" -> markDraft()
        "finish" -> finish(prompt("Task 식별자"))
        "exit" -> Unit
    }
}

fun usage(): Nothing = error(
    """
    사용법:
      kotlin scripts/git/git-workflow.main.kts start <task-id>
      kotlin scripts/git/git-workflow.main.kts publish "[<task-id>] Feature: <description>" --body-file <path>
      kotlin scripts/git/git-workflow.main.kts update
      kotlin scripts/git/git-workflow.main.kts ready
      kotlin scripts/git/git-workflow.main.kts draft
      kotlin scripts/git/git-workflow.main.kts finish <task-id>
    """.trimIndent(),
)

fun dispatch(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        null -> interactive()
        "start" -> {
            if (arguments.size != 2) usage()
            start(arguments[1])
        }
        "publish" -> {
            if (arguments.size != 4 || arguments[2] != "--body-file") usage()
            publish(arguments[1], arguments[3])
        }
        "update" -> {
            if (arguments.size != 1) usage()
            update()
        }
        "ready" -> {
            if (arguments.size != 1) usage()
            markReady()
        }
        "draft" -> {
            if (arguments.size != 1) usage()
            markDraft()
        }
        "finish" -> {
            if (arguments.size != 2) usage()
            finish(arguments[1])
        }
        else -> usage()
    }
}

try {
    dispatch(args)
} catch (exception: WorkflowException) {
    System.err.println(exception.payload.render())
    exitProcess(if (exception.payload.result == WorkflowResult.RECOVERY_REQUIRED) 2 else 1)
} catch (exception: Exception) {
    System.err.println(
        WorkflowPayload(
            WorkflowResult.FAILED,
            args.firstOrNull() ?: "interactive",
            "COMMAND",
            exception.message ?: exception::class.simpleName.orEmpty(),
        ).render(),
    )
    exitProcess(1)
}
