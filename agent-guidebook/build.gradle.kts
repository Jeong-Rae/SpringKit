plugins {
  application
  kotlin("jvm") version "2.3.21"
  id("com.diffplug.spotless") version "8.10.2"
}

group = "com.springkit.agentguidebook"

version = "0.0.1-SNAPSHOT"

application {
  mainClass.set("com.springkit.agentguidebook.codex.MainKt")
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
  runtimeOnly("org.slf4j:slf4j-nop:2.0.18")
  implementation("org.jetbrains.exposed:exposed-core:1.6.0")
  implementation("org.jetbrains.exposed:exposed-jdbc:1.6.0")
  implementation("org.xerial:sqlite-jdbc:3.53.4.0")
  implementation("org.yaml:snakeyaml:2.6")

  testImplementation("io.kotest:kotest-assertions-core:6.2.4")
  testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
  useJUnitPlatform()
  dependsOn(tasks.named("installDist"))
  systemProperty(
      "agentGuidebook.applicationExecutable",
      layout.buildDirectory
          .file("install/agent-guidebook/bin/agent-guidebook")
          .get()
          .asFile
          .absolutePath,
  )
  systemProperty("agentGuidebook.testRuntimeClasspath", sourceSets["test"].runtimeClasspath.asPath)
}

spotless {
  isEnforceCheck = false

  kotlin {
    target("src/*/kotlin/**/*.kt")
    ktfmt("0.63").metaStyle()
  }

  kotlinGradle {
    target("**/*.gradle.kts")
    targetExclude("**/.gradle/**", "**/build/**")
    ktfmt("0.63").metaStyle()
  }
}
