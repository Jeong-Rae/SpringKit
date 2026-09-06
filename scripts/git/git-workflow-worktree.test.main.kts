#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Files

data class TestCommandResult(val exitCode: Int, val output: String)

fun runCommand(
    workingDirectory: File,
    vararg command: String,
    environment: Map<String, String> = emptyMap(),
    check: Boolean = true,
): TestCommandResult {
    val process = ProcessBuilder(*command)
        .directory(workingDirectory)
        .redirectErrorStream(true)
        .apply { environment().putAll(environment) }
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (check && exitCode != 0) {
        error("명령 실행에 실패했습니다 (${command.joinToString(" ")}).\n$output")
    }
    return TestCommandResult(exitCode, output.trim())
}

fun git(workingDirectory: File, vararg arguments: String, check: Boolean = true): TestCommandResult =
    runCommand(workingDirectory, "git", *arguments, check = check)

val projectRoot = File(System.getProperty("user.dir"))
val workflowSource = File(projectRoot, "scripts/git/git-workflow.main.kts")
require(workflowSource.isFile) { "테스트 대상 스크립트를 찾을 수 없습니다: ${workflowSource.path}" }

val fixtureRoot = Files.createTempDirectory("git-workflow-worktree-").toFile()
try {
    val remote = File(fixtureRoot, "origin.git")
    val repository = File(fixtureRoot, "repository")
    val featureWorktree = File(fixtureRoot, "feature-worktree")
    val fakeBin = File(fixtureRoot, "bin").apply { mkdirs() }
    val buildMarker = File(fixtureRoot, "build-ran")

    git(fixtureRoot, "init", "--bare", remote.absolutePath)
    git(fixtureRoot, "init", "--initial-branch=develop", repository.absolutePath)
    git(repository, "config", "user.name", "Workflow Test")
    git(repository, "config", "user.email", "workflow-test@example.com")

    val workflow = File(repository, "scripts/git/git-workflow.main.kts")
    workflow.parentFile.mkdirs()
    workflowSource.copyTo(workflow)
    File(repository, "gradlew").apply {
        writeText(
            """
            #!/bin/sh
            test "${'$'}1" = "build" || exit 1
            touch "${'$'}BUILD_MARKER"
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }
    File(repository, "README.md").writeText("base\n")
    git(repository, "add", ".")
    git(repository, "commit", "-m", "Build: 테스트 저장소 구성")
    val baseCommit = git(repository, "rev-parse", "HEAD").output
    git(repository, "remote", "add", "origin", remote.absolutePath)
    git(repository, "push", "-u", "origin", "develop")

    git(repository, "switch", "-c", "feature/sk-test")
    File(repository, "feature.txt").writeText("feature\n")
    git(repository, "add", "feature.txt")
    git(repository, "commit", "-m", "Feature: 테스트 변경 추가")
    val headCommit = git(repository, "rev-parse", "HEAD").output
    git(repository, "push", "-u", "origin", "feature/sk-test")

    git(repository, "switch", "develop")
    git(repository, "merge", "--squash", "feature/sk-test")
    git(repository, "commit", "-m", "Feature: 테스트 변경 병합")
    val mergeCommit = git(repository, "rev-parse", "HEAD").output
    git(repository, "push", "origin", "develop")
    git(repository, "reset", "--hard", baseCommit)
    git(repository, "worktree", "add", featureWorktree.absolutePath, "feature/sk-test")

    File(fakeBin, "gh").apply {
        writeText(
            """
            #!/bin/sh
            case "${'$'}2" in
              list) printf '%s\n' "${'$'}PR_URL" ;;
              view) printf '%s\tdevelop\tfalse\t%s\t%s\t%s\n' MERGED "${'$'}HEAD_COMMIT" "${'$'}MERGE_COMMIT" "${'$'}PR_URL" ;;
              *) exit 1 ;;
            esac
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    val environment = mapOf(
        "PATH" to "${fakeBin.absolutePath}:${System.getenv("PATH")}",
        "PR_URL" to "https://example.test/pull/1",
        "HEAD_COMMIT" to headCommit,
        "MERGE_COMMIT" to mergeCommit,
        "BUILD_MARKER" to buildMarker.absolutePath,
    )
    val result = runCommand(
        featureWorktree,
        "kotlin",
        workflow.absolutePath,
        "finish",
        "sk-test",
        environment = environment,
        check = false,
    )
    require(result.exitCode == 0) { "worktree finish가 실패했습니다.\n${result.output}" }

    require(git(repository, "rev-parse", "develop").output == mergeCommit) {
        "develop이 원격 병합 커밋으로 갱신되지 않았습니다."
    }
    require(buildMarker.isFile) { "develop worktree에서 Gradle build를 실행하지 않았습니다." }
    require(git(repository, "show-ref", "--verify", "--quiet", "refs/heads/feature/sk-test", check = false).exitCode != 0) {
        "로컬 feature 브랜치를 삭제하지 않았습니다."
    }
    require(git(repository, "ls-remote", "--heads", "origin", "feature/sk-test").output.isBlank()) {
        "원격 feature 브랜치를 삭제하지 않았습니다."
    }
    require(git(featureWorktree, "branch", "--show-current").output.isBlank()) {
        "feature worktree를 detached HEAD로 전환하지 않았습니다."
    }
    require(git(featureWorktree, "rev-parse", "HEAD").output == mergeCommit) {
        "feature worktree가 갱신된 develop 커밋을 가리키지 않습니다."
    }

    println("PASS: 별도 worktree에서 finish를 완료했습니다.")
} finally {
    fixtureRoot.deleteRecursively()
}
