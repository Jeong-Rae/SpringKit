plugins {
  kotlin("jvm") version "2.2.21" apply false
  kotlin("plugin.spring") version "2.2.21" apply false
  id("org.springframework.boot") version "4.1.1" apply false
  id("io.spring.dependency-management") version "1.1.7" apply false
  id("com.epages.restdocs-api-spec") version "0.20.1" apply false
  id("com.diffplug.spotless") version "8.10.2"
}

val spotlessRatchetFrom =
    providers
        .gradleProperty("spotlessRatchetFrom")
        .orElse(providers.environmentVariable("SPOTLESS_RATCHET_FROM"))
        .getOrElse("HEAD")

spotless {
  isEnforceCheck = false
  ratchetFrom(spotlessRatchetFrom)

  kotlinGradle {
    target("**/*.gradle.kts")
    targetExclude("**/.gradle/**", "**/build/**")
    ktfmt("0.63").metaStyle()
  }
}

allprojects {
  group = "__SPRINGKIT_GROUP__"

  version = "0.0.1-SNAPSHOT"

  repositories {
    mavenCentral()
  }
}

subprojects {
  apply(plugin = "com.diffplug.spotless")

  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    isEnforceCheck = false
    ratchetFrom(spotlessRatchetFrom)

    kotlin {
      target("src/*/kotlin/**/*.kt")
      ktfmt("0.63").metaStyle()
    }
  }
}

tasks.named("spotlessCheck") {
  dependsOn(subprojects.map { it.tasks.named("spotlessCheck") })
}

tasks.named("spotlessApply") {
  dependsOn(subprojects.map { it.tasks.named("spotlessApply") })
}
