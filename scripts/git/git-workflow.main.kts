#!/usr/bin/env kotlin

import java.io.File

data class CommandResult(val exitCode: Int, val output: String)

data class PullRequestStatus(
    val branch: String,
    val isDraft: Boolean,
    val url: String,
)

fun execute(
    workingDirectory: File,
    vararg command: String,
    captureOutput: Boolean = false,
    check: Boolean = true,
): CommandResult {
    val builder = ProcessBuilder(*command).directory(workingDirectory)
    val process = if (captureOutput) {
        builder.redirectErrorStream(true).start()
    } else {
        builder.inheritIO().start()
    }
    val output = if (captureOutput) process.inputStream.bufferedReader().use { it.readText() } else ""
    val exitCode = process.waitFor()

    if (check && exitCode != 0) {
        val detail = output.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        error("명령 실행에 실패했습니다 (${command.joinToString(" ")}).$detail")
    }
    return CommandResult(exitCode, output.trim())
}

fun commandOutput(workingDirectory: File, vararg command: String): String =
    execute(workingDirectory, *command, captureOutput = true).output

val invocationDirectory = File(System.getProperty("user.dir"))
val repositoryRoot = File(commandOutput(invocationDirectory, "git", "rev-parse", "--show-toplevel"))

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

fun requireDevelop() {
    require(currentBranch() == "develop") { "현재 브랜치가 develop이어야 합니다." }
}

fun requireCleanTrackedFiles() {
    val changed = commandOutput(repositoryRoot, "git", "status", "--short", "--untracked-files=no")
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

fun remoteBranchExists(branch: String): Boolean =
    commandOutput(repositoryRoot, "git", "ls-remote", "--heads", "origin", branch).isNotBlank()

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

fun finish(taskId: String) {
    requireTaskId(taskId)
    requireCommand("gh")
    requireCleanTrackedFiles()

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
    require(pullRequestUrl.isNotBlank()) { "feature의 PR을 찾을 수 없습니다: $branch" }

    var pr = commandOutput(
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

    require(pr.size == 6) { "PR 상태 응답을 해석할 수 없습니다." }
    var state = pr[0]
    var baseBranch = pr[1]
    val isDraft = pr[2]
    val headCommit = pr[3]
    var mergeCommit = pr[4]
    var prUrl = pr[5]
    require(baseBranch == "develop") { "PR 대상 브랜치가 develop이 아닙니다: $baseBranch" }
    require(state == "OPEN" || state == "MERGED") { "병합할 수 없는 PR 상태입니다: $prUrl ($state)" }

    if (branchExists(branch)) {
        val localFeature = commandOutput(repositoryRoot, "git", "rev-parse", branch)
        require(localFeature == headCommit) {
            "로컬 $branch 에 게시되지 않은 커밋이 있습니다. update를 먼저 실행하세요."
        }
    }

    if (state == "OPEN") {
        require(branchExists(branch)) { "병합할 로컬 feature 브랜치를 찾을 수 없습니다: $branch" }
        require(isDraft == "false") { "초안 PR은 병합할 수 없습니다. ready 명령을 먼저 실행하세요: $prUrl" }
        execute(
            repositoryRoot,
            "gh",
            "pr",
            "merge",
            prUrl,
            "--squash",
            "--delete-branch",
            "--match-head-commit",
            headCommit,
        )

        pr = commandOutput(
            repositoryRoot,
            "gh",
            "pr",
            "view",
            prUrl,
            "--json",
            "state,baseRefName,isDraft,headRefOid,mergeCommit,url",
            "--jq",
            "[.state, .baseRefName, (.isDraft | tostring), .headRefOid, (.mergeCommit.oid // \"\"), .url] | @tsv",
        ).split('\t')
        require(pr.size == 6) { "병합 후 PR 상태 응답을 해석할 수 없습니다." }
        state = pr[0]
        baseBranch = pr[1]
        mergeCommit = pr[4]
        prUrl = pr[5]
    }

    require(state == "MERGED") { "PR squash 병합이 완료되지 않았습니다: $prUrl ($state)" }
    require(baseBranch == "develop") { "PR 대상 브랜치가 develop이 아닙니다: $baseBranch" }
    require(mergeCommit.isNotBlank()) { "PR의 squash commit을 확인할 수 없습니다: $prUrl" }

    execute(repositoryRoot, "git", "switch", "develop")
    execute(repositoryRoot, "git", "pull", "--ff-only", "origin", "develop")

    require(isAncestor(mergeCommit, "develop")) {
        "원격 PR의 squash commit이 로컬 develop에 포함되지 않았습니다: $mergeCommit"
    }
    val mergeCommitFields = commandOutput(
        repositoryRoot,
        "git",
        "rev-list",
        "--parents",
        "-n",
        "1",
        mergeCommit,
    ).split(' ')
    require(mergeCommitFields.size == 2) {
        "PR이 squash 방식으로 병합되지 않았습니다. 로컬 feature를 정리하지 않습니다: $prUrl"
    }

    val gradleWrapper = File(repositoryRoot, "gradlew")
    require(gradleWrapper.isFile) { "Gradle wrapper를 찾을 수 없습니다: ${gradleWrapper.path}" }
    execute(repositoryRoot, gradleWrapper.absolutePath, "build")

    if (remoteBranchExists(branch)) {
        execute(repositoryRoot, "git", "push", "origin", "--delete", branch)
    }
    if (branchExists(branch)) {
        execute(repositoryRoot, "git", "branch", "-D", branch)
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

when (args.firstOrNull()) {
    "start" -> {
        if (args.size != 2) usage()
        start(args[1])
    }
    "publish" -> {
        if (args.size != 4 || args[2] != "--body-file") usage()
        publish(args[1], args[3])
    }
    "update" -> {
        if (args.size != 1) usage()
        update()
    }
    "ready" -> {
        if (args.size != 1) usage()
        markReady()
    }
    "draft" -> {
        if (args.size != 1) usage()
        markDraft()
    }
    "finish" -> {
        if (args.size != 2) usage()
        finish(args[1])
    }
    else -> usage()
}
