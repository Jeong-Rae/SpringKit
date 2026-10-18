plugins {
  kotlin("jvm") version "2.3.21"
  id("com.diffplug.spotless") version "8.10.2"
}

group = "com.springkit.agentguidebook"

version = "0.0.1-SNAPSHOT"

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
  implementation("org.xerial:sqlite-jdbc:3.53.4.0")
  implementation("org.yaml:snakeyaml:2.6")

  testImplementation("io.kotest:kotest-assertions-core:6.2.4")
  testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
  useJUnitPlatform()
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
