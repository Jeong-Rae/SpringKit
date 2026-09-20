import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
  kotlin("jvm") version "2.4.20"
  kotlin("plugin.serialization") version "2.4.20"
  application
  id("com.diffplug.spotless") version "8.10.2"
  id("org.graalvm.buildtools.native") version "1.1.14"
}

group = "io.springkit"

version = "0.1.0-SNAPSHOT"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories {
  mavenCentral()
}

application {
  mainClass.set("io.springkit.workflow.MainKt")
}

dependencies {
  implementation("com.github.ajalt.clikt:clikt:5.0.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("com.squareup.okio:okio:3.18.1")

  testImplementation("io.kotest:kotest-assertions-core:6.2.4")
  testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
  testImplementation("com.squareup.okio:okio-fakefilesystem:3.18.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(21)
}

spotless {
  isEnforceCheck = false

  kotlin {
    target("src/*/kotlin/**/*.kt")
    ktfmt("0.63").metaStyle()
  }

  kotlinGradle {
    target("*.gradle.kts")
    ktfmt("0.63").metaStyle()
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

graalvmNative {
  binaries {
    named("main") {
      imageName = "workflow"
    }
  }
}

val installWorkflowNative =
    tasks.register<Copy>("installWorkflowNative") {
      dependsOn("nativeCompile")
      from(layout.buildDirectory.dir("native/nativeCompile")) {
        include("workflow")
      }
      into(layout.projectDirectory.dir("../tools"))
    }

/** Native 바이너리의 시작과 JSON 결과 계약을 확인하는 최소 실행 검증입니다. */
abstract class WorkflowNativeSmokeTest : DefaultTask() {
  @get:Inject abstract val execOperations: ExecOperations

  @TaskAction
  fun verifyNativeBinary() {
    val repositoryRoot = project.layout.projectDirectory.dir("..").asFile
    val executable = project.layout.projectDirectory.file("../tools/workflow").asFile
    val stateFile = temporaryDir.resolve("state.json")
    stateFile.writeText(
        """
        {
          "schema_version": 1,
          "store_revision": 0,
          "tasks": {
            "smoke-task": {
              "id": "smoke-task",
              "external_id": {"value": "SMOKE-1"},
              "title": "Native smoke task",
              "state": "OPEN",
              "sub_task_ids": []
            }
          }
        }
        """
            .trimIndent()
    )

    val help = invoke(executable, repositoryRoot, stateFile, "--help")
    check(help.exitCode == 0) {
      "Native 바이너리의 --help 실행이 실패했습니다. 종료 코드: ${help.exitCode}\n${help.stderr}"
    }
    check(help.stdout.contains("Usage: workflow")) {
      "Native 바이너리가 도움말을 출력하지 않았습니다.\n${help.stdout}"
    }

    val success =
        invoke(
            executable,
            repositoryRoot,
            stateFile,
            "status",
            "--task",
            "smoke-task",
            "--json",
        )
    requireJsonResult(success, expectedType = "success", expectedExitCode = 0)

    val failure =
        invoke(
            executable,
            repositoryRoot,
            stateFile,
            "status",
            "--subtask",
            "missing-subtask",
            "--json",
        )
    val failureJson = requireJsonResult(failure, expectedType = "failure", expectedExitCode = 1)
    val failureData = failureJson["data"] as? Map<*, *>
    check(failureData?.get("code") == "SUBTASK_NOT_FOUND") {
      "대표 JSON 실패 코드가 예상과 다릅니다.\n${failure.stdout}"
    }
  }

  private fun invoke(
      executable: File,
      repositoryRoot: File,
      stateFile: File,
      vararg arguments: String,
  ): NativeInvocation {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result = execOperations.exec {
      commandLine(executable.absolutePath, *arguments)
      workingDir(repositoryRoot)
      environment("WORKFLOW_REPO_ROOT", repositoryRoot.absolutePath)
      environment("WORKFLOW_STATE_FILE", stateFile.absolutePath)
      standardOutput = stdout
      errorOutput = stderr
      isIgnoreExitValue = true
    }
    return NativeInvocation(
        exitCode = result.exitValue,
        stdout = stdout.toString(StandardCharsets.UTF_8.name()),
        stderr = stderr.toString(StandardCharsets.UTF_8.name()),
    )
  }

  private fun requireJsonResult(
      invocation: NativeInvocation,
      expectedType: String,
      expectedExitCode: Int,
  ): Map<*, *> {
    check(invocation.exitCode == expectedExitCode) {
      "JSON 경로의 종료 코드가 다릅니다. " +
          "기대값: $expectedExitCode, 실제값: ${invocation.exitCode}\n${invocation.stderr}"
    }
    val lines = invocation.stdout.lineSequence().filter(String::isNotBlank).toList()
    check(lines.size == 1) {
      "JSON 경로는 표준 출력에 JSON 객체 하나만 기록해야 합니다.\n${invocation.stdout}"
    }
    val json = lines.single().trim()
    val parsed = JsonSlurper().parseText(json) as? Map<*, *>
    check(parsed != null) { "JSON 경로가 객체를 출력하지 않았습니다.\n$json" }
    check(parsed["type"] == expectedType) {
      "JSON 결과 type이 다릅니다. 기대값: $expectedType\n$json"
    }
    check(parsed["data"] is Map<*, *>) {
      "JSON 결과에 data가 없습니다.\n$json"
    }
    return parsed
  }

  private data class NativeInvocation(
      val exitCode: Int,
      val stdout: String,
      val stderr: String,
  )
}

tasks.register<WorkflowNativeSmokeTest>("workflowNativeSmokeTest") {
  dependsOn(installWorkflowNative)
}
