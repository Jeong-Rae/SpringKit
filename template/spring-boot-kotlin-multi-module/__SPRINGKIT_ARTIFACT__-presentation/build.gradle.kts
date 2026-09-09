plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.epages.restdocs-api-spec")
}

base {
    archivesName = "__SPRINGKIT_ARTIFACT__"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":__SPRINGKIT_ARTIFACT__-application"))
    runtimeOnly(project(":__SPRINGKIT_ARTIFACT__-infrastructure"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":declarative-rest-docs"))
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.4")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // 참고: https://kotlinlang.org/docs/java-interop.html#jsr-305-support
        // 참고: https://kotlinlang.org/docs/whatsnew22.html#new-defaulting-rules-for-use-site-annotation-targets
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

configure<com.epages.restdocs.apispec.gradle.OpenApi3Extension> {
    setServer("http://localhost")
    title = "__SPRINGKIT_PROJECT_NAME__ API"
    description = "__SPRINGKIT_PROJECT_NAME__ REST API"
    version = "1.0.0"
    format = "yaml"
}

tasks.withType<com.epages.restdocs.apispec.gradle.OpenApi3Task>().configureEach {
    dependsOn(tasks.named("test"))
    notCompatibleWithConfigurationCache(
        "restdocs-api-spec 0.20.1의 OpenApi3Task는 Jackson 상태를 직렬화할 수 없습니다.",
    )
}

val openApiTestSourceSet = sourceSets.create("openApiTest")

configurations.named(openApiTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(openApiTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val openApiTest =
    tasks.register<Test>("openApiTest") {
        description = "생성된 OpenAPI 기준선의 의미를 검증합니다."
        group = "verification"
        testClassesDirs = openApiTestSourceSet.output.classesDirs
        classpath = openApiTestSourceSet.runtimeClasspath
        dependsOn(tasks.withType<com.epages.restdocs.apispec.gradle.OpenApi3Task>())
        useJUnitPlatform()
    }

tasks.named("build") {
    dependsOn(openApiTest)
}
