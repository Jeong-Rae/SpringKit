import io.springkit.workflow.build.WorkflowNativeSmokeTest

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
    target("src/*/kotlin/**/*.kt", "buildSrc/src/**/*.kt")
    ktfmt("0.63").metaStyle()
  }

  kotlinGradle {
    target("*.gradle.kts", "buildSrc/*.gradle.kts")
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
      buildArgs.add("--initialize-at-build-time=kotlin.DeprecationLevel")
    }
  }
}

val installWorkflowNative =
    tasks.register<Copy>("installWorkflowNative") {
      dependsOn("nativeCompile")
      from(layout.buildDirectory.dir("native/nativeCompile")) {
        include("workflow")
      }
      into(layout.projectDirectory.dir("bin"))
    }

tasks.register<WorkflowNativeSmokeTest>("workflowNativeSmokeTest") {
  dependsOn(installWorkflowNative)
  repositoryRoot.set(layout.projectDirectory.dir("../.."))
  executable.set(layout.projectDirectory.file("bin/workflow"))
}
