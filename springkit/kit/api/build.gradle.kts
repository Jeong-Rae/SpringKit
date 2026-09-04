plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":springkit:kit:client"))
    implementation(project(":springkit:kit:common"))
    implementation(project(":springkit:kit:core"))
    implementation(project(":springkit:kit:service"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "me.jeongrae.springkit.kit.api.SpringKitKt"
}

tasks.test {
    useJUnitPlatform()
}
