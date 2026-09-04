plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "me.jeongrae"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":springkit:kit:client"))
    implementation(project(":springkit:kit:core"))
    implementation(project(":springkit:kit:service"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "me.jeongrae.springkit.SpringKitKt"
}

tasks.test {
    useJUnitPlatform()
}
