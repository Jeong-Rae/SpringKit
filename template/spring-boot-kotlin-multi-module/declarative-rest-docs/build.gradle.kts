plugins {
  `java-library`
  kotlin("jvm")
  id("io.spring.dependency-management")
  id("com.epages.restdocs-api-spec")
}

base {
  archivesName = "declarative-rest-docs"
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencyManagement {
  imports {
    mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
  }
}

dependencies {
  api("com.epages:restdocs-api-spec:0.20.1")
  api("org.junit.jupiter:junit-jupiter-api")
  api("org.springframework.boot:spring-boot-test")
  api("org.springframework.restdocs:spring-restdocs-mockmvc")
  api("tools.jackson.core:jackson-databind")
  testImplementation("jakarta.servlet:jakarta.servlet-api")
  testImplementation("org.springframework:spring-webmvc")
  testImplementation("org.yaml:snakeyaml")
  testImplementation("io.kotest:kotest-assertions-core:6.2.4")
  testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  compilerOptions {
    // 참고: https://kotlinlang.org/docs/java-interop.html#jsr-305-support
    // 참고:
    // https://kotlinlang.org/docs/whatsnew22.html#new-defaulting-rules-for-use-site-annotation-targets
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

val compilerContractSnippets = layout.buildDirectory.dir("generated-snippets/compiler-contract")
val compilerOpenApiSnippets = compilerContractSnippets.map { it.dir("compiler") }
val compilerRepeatSnippets = compilerContractSnippets.map { it.dir("compiler-repeat") }
val manualBaselineSnippets = compilerContractSnippets.map { it.dir("manual") }
val compilerOpenApiDocument = layout.buildDirectory.file("api-spec/openapi3.yaml")
val compilerRepeatOpenApiDocument = layout.buildDirectory.file("api-spec-repeat/openapi3.yaml")
val compilerOpenApiTestSourceSet = sourceSets.create("compilerOpenApiTest")

compilerOpenApiTestSourceSet.compileClasspath += sourceSets.main.get().output

compilerOpenApiTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations.named(compilerOpenApiTestSourceSet.implementationConfigurationName) {
  extendsFrom(configurations.testImplementation.get())
}

configurations.named(compilerOpenApiTestSourceSet.runtimeOnlyConfigurationName) {
  extendsFrom(configurations.testRuntimeOnly.get())
}

val cleanCompilerOpenApiSnippets =
    tasks.register<Delete>("cleanCompilerOpenApiSnippets") {
      delete(compilerContractSnippets)
    }

val compilerOpenApiTest =
    tasks.register<Test>("compilerOpenApiTest") {
      description = "Compiler 결과로 OpenAPI 집계 입력을 생성합니다."
      group = "verification"
      testClassesDirs = compilerOpenApiTestSourceSet.output.classesDirs
      classpath = compilerOpenApiTestSourceSet.runtimeClasspath
      outputs.dir(compilerContractSnippets)
      systemProperty(
          "springkit.compiler-openapi.snippets",
          compilerOpenApiSnippets.get().asFile.absolutePath,
      )
      systemProperty(
          "springkit.compiler-repeat.snippets",
          compilerRepeatSnippets.get().asFile.absolutePath,
      )
      systemProperty(
          "springkit.manual-baseline.snippets",
          manualBaselineSnippets.get().asFile.absolutePath,
      )
      dependsOn(cleanCompilerOpenApiSnippets)
      useJUnitPlatform()
    }

configure<com.epages.restdocs.apispec.gradle.OpenApi3Extension> {
  setServer("http://localhost")
  title = "__SPRINGKIT_PROJECT_NAME__ API"
  description = "__SPRINGKIT_PROJECT_NAME__ REST API"
  version = "1.0.0"
  format = "yaml"
  snippetsDirectory = compilerOpenApiSnippets.get().asFile.path
}

val openApi3Extension = extensions.getByType<com.epages.restdocs.apispec.gradle.OpenApi3Extension>()
val compilerRepeatOpenApi3 =
    tasks.register<com.epages.restdocs.apispec.gradle.OpenApi3Task>("compilerRepeatOpenApi3") {
      applyExtension(openApi3Extension)
      snippetsDirectory = compilerRepeatSnippets.get().asFile.path
      outputDirectory = layout.buildDirectory.dir("api-spec-repeat").get().asFile.path
    }

tasks.withType<com.epages.restdocs.apispec.gradle.OpenApi3Task>().configureEach {
  dependsOn(compilerOpenApiTest)
  notCompatibleWithConfigurationCache(
      "restdocs-api-spec 0.20.1의 OpenApi3Task는 Jackson 상태를 직렬화할 수 없습니다."
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
      inputs.files(compilerOpenApiDocument, compilerRepeatOpenApiDocument)
      useJUnitPlatform()
    }

tasks.named("build") {
  dependsOn(openApiTest)
}
