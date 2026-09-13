plugins {
    kotlin("jvm") version "2.3.21"
    application
    id("com.diffplug.spotless") version "8.10.2"
    id("org.graalvm.buildtools.native") version "1.1.12"
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

    testImplementation(kotlin("test-junit5"))
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
            // Keep native builds deterministic and require a real native image.
            fallback = false
        }
    }
}

val installWorkflowNative = tasks.register<Copy>("installWorkflowNative") {
    dependsOn("nativeCompile")
    from(layout.buildDirectory.dir("native/nativeCompile")) {
        include("workflow")
    }
    into(layout.projectDirectory.dir("../tools"))
}

tasks.register<Exec>("workflowNativeSmokeTest") {
    dependsOn(installWorkflowNative)
    workingDir(layout.projectDirectory.dir(".."))
    commandLine(layout.projectDirectory.file("../tools/workflow").asFile.absolutePath, "--help")
}
