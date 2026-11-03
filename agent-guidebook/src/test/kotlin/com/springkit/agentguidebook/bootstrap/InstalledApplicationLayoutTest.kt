package com.springkit.agentguidebook.bootstrap

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InstalledApplicationLayoutTest :
    FeatureSpec({
      val applicationExecutable =
          Path.of(
              checkNotNull(System.getProperty("agentGuidebook.applicationExecutable")) {
                "Gradle installDist 실행 파일 경로가 전달되지 않았습니다."
              }
          )
      val installationDirectory =
          checkNotNull(applicationExecutable.parent?.parent) {
            "설치 디렉터리를 실행 파일 경로에서 찾을 수 없습니다."
          }

      feature("설치된 Agent Guidebook 파일 구성") {
        scenario("실행 파일, 런타임 JAR, 실행 가능한 Hook과 JSON 예제를 함께 제공합니다.") {
          val executable = installationDirectory.resolve("bin/agent-guidebook")
          val windowsExecutable = installationDirectory.resolve("bin/agent-guidebook.bat")
          val libraryDirectory = installationDirectory.resolve("lib")
          val runtimeJars =
              if (Files.isDirectory(libraryDirectory)) {
                Files.list(libraryDirectory).use { files ->
                  files
                      .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                      .toList()
                }
              } else {
                emptyList()
              }
          val hookScript = installationDirectory.resolve("hooks/post-tool-use.sh")
          val hookExample = installationDirectory.resolve("hooks/hooks.example.json")
          val missingEntries = buildList {
            if (!Files.isRegularFile(executable)) add(executable.toString())
            if (!Files.isRegularFile(windowsExecutable)) add(windowsExecutable.toString())
            if (runtimeJars.isEmpty()) add("${libraryDirectory}/*.jar")
            if (!Files.isRegularFile(hookScript)) add(hookScript.toString())
            if (!Files.isRegularFile(hookExample)) add(hookExample.toString())
          }

          withClue("installDist 필수 파일 누락: ${missingEntries.joinToString()}") {
            missingEntries.shouldBeEmpty()
          }
          withClue("Hook 실행 파일 권한 누락: $hookScript") {
            Files.isExecutable(hookScript) shouldBe true
          }

          val configuration = Json.parseToJsonElement(Files.readString(hookExample)).jsonObject
          val postToolUseHooks =
              configuration.getValue("hooks").jsonObject.getValue("PostToolUse").jsonArray
          val applyPatchRegistration = postToolUseHooks.singleOrNull {
            it.jsonObject.getValue("matcher").jsonPrimitive.content == "^apply_patch$"
          }

          val applyPatchHook =
              withClue("PostToolUse의 apply_patch matcher가 없습니다.") {
                applyPatchRegistration.shouldNotBeNull()
              }

          val commandHook =
              applyPatchHook.jsonObject.getValue("hooks").jsonArray.singleOrNull {
                it.jsonObject.getValue("type").jsonPrimitive.content == "command"
              }

          val configuredCommandHook =
              withClue("apply_patch matcher에 command Hook이 없습니다.") {
                commandHook.shouldNotBeNull()
              }

          val commandConfiguration = configuredCommandHook.jsonObject
          val command = commandConfiguration.getValue("command").jsonPrimitive.content
          val timeoutSeconds = commandConfiguration.getValue("timeout").jsonPrimitive.int

          command shouldContain "hooks/post-tool-use.sh"
          (timeoutSeconds > 0) shouldBe true
        }
      }
    })
