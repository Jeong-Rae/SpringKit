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
    languageVersion = JavaLanguageVersion.of(24)
  }
}

dependencyManagement {
  imports {
    mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES) {
      bomProperty("kotlin.version", "2.2.21")
    }
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

val springFixtureSnippets = layout.buildDirectory.dir("generated-snippets/spring-fixtures")
val springFixtureGenerated = layout.projectDirectory.dir("fixtures/spring-restdocs/generated")
val springFixtureGeneratedOpenApiJson =
    layout.buildDirectory.dir("api-spec/spring-fixtures/generated/json")
val springFixtureGeneratedOpenApiYaml =
    layout.buildDirectory.dir("api-spec/spring-fixtures/generated/yaml")
val springFixtureOpenApiJsonOutput = springFixtureGenerated.file("openapi/json/openapi3.json")
val springFixtureOpenApiYamlOutput = springFixtureGenerated.file("openapi/yaml/openapi3.yaml")
val springTestSourceSet = sourceSets.create("springTest")

kotlin.target.compilations
    .getByName(springTestSourceSet.name)
    .associateWith(kotlin.target.compilations.getByName("main"))

springTestSourceSet.compileClasspath += sourceSets.main.get().output

springTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations.named(springTestSourceSet.implementationConfigurationName) {
  extendsFrom(configurations.testImplementation.get())
}

configurations.named(springTestSourceSet.runtimeOnlyConfigurationName) {
  extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
  add(
      springTestSourceSet.implementationConfigurationName,
      "org.springframework.boot:spring-boot-starter-web",
  )
  add(
      springTestSourceSet.implementationConfigurationName,
      "org.springframework.boot:spring-boot-starter-test",
  )
}

val cleanSpringFixtureSnippets =
    tasks.register<Delete>("cleanSpringFixtureSnippets") {
      delete(springFixtureSnippets)
    }

val springTest =
    tasks.register<Test>("springTest") {
      description = "Spring MVC fixture로 선언형 REST Docs 통합 동작을 검증합니다."
      group = "verification"
      testClassesDirs = springTestSourceSet.output.classesDirs
      classpath = springTestSourceSet.runtimeClasspath
      outputs.dir(springFixtureSnippets)
      systemProperty(
          "springkit.restdocs.snippets",
          springFixtureSnippets.get().asFile.absolutePath,
      )
      dependsOn(cleanSpringFixtureSnippets)
      useJUnitPlatform()
    }

val compilerContractSnippets = layout.buildDirectory.dir("generated-snippets/compiler-contract")
val compilerOpenApiSnippets = compilerContractSnippets.map { it.dir("compiler") }
val compilerRepeatSnippets = compilerContractSnippets.map { it.dir("compiler-repeat") }
val manualBaselineSnippets = compilerContractSnippets.map { it.dir("manual") }
val compilerOpenApiDocument = layout.buildDirectory.file("api-spec/openapi3.yaml")
val compilerRepeatOpenApiDocument = layout.buildDirectory.file("api-spec-repeat/openapi3.yaml")
val compilerOpenApiGeneratedDocument =
    layout.buildDirectory.file("api-spec/generated/openapi3.yaml")
val compilerRepeatOpenApiGeneratedDocument =
    layout.buildDirectory.file("api-spec-repeat/generated/openapi3.yaml")
val compilerOpenApiSupplementJson = layout.buildDirectory.file("api-spec/supplement/openapi3.json")
val compilerRepeatOpenApiSupplementJson =
    layout.buildDirectory.file("api-spec-repeat/supplement/openapi3.json")
val compilerOpenApiTestSourceSet = sourceSets.create("compilerOpenApiTest")

kotlin.target.compilations
    .getByName(compilerOpenApiTestSourceSet.name)
    .associateWith(kotlin.target.compilations.getByName("main"))

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
      outputDirectory = layout.buildDirectory.dir("api-spec-repeat/generated").get().asFile.path
    }

val springFixtureOpenApiJson =
    tasks.register<com.epages.restdocs.apispec.gradle.OpenApi3Task>("springFixtureOpenApiJson") {
      description = "Spring fixture resource.json을 OpenAPI JSON으로 집계합니다."
      group = "verification"
      applyExtension(openApi3Extension)
      snippetsDirectory = springFixtureSnippets.get().asFile.path
      outputDirectory = springFixtureGeneratedOpenApiJson.get().asFile.path
      outputFileNamePrefix = "openapi3"
      format = "json"
      dependsOn(springTest)
      doFirst {
        springFixtureGeneratedOpenApiJson.get().asFile.mkdirs()
      }
    }

val springFixtureOpenApiYaml =
    tasks.register<com.epages.restdocs.apispec.gradle.OpenApi3Task>("springFixtureOpenApiYaml") {
      description = "Spring fixture resource.json을 OpenAPI YAML으로 집계합니다."
      group = "verification"
      applyExtension(openApi3Extension)
      snippetsDirectory = springFixtureSnippets.get().asFile.path
      outputDirectory = springFixtureGeneratedOpenApiYaml.get().asFile.path
      outputFileNamePrefix = "openapi3"
      format = "yaml"
      dependsOn(springTest)
      doFirst {
        springFixtureGeneratedOpenApiYaml.get().asFile.mkdirs()
      }
    }

val openApi3Supplement =
    tasks.register<JavaExec>("openApi3Supplement") {
      description = "기본 compiler OpenAPI 산출물에서 raw/multipart 본문 표현을 보완합니다."
      group = "verification"
      dependsOn("openapi3", tasks.named("testClasses"))
      classpath = sourceSets.test.get().runtimeClasspath
      mainClass.set(
          "__SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestBodyOpenApiSupplementTestKt"
      )
      args(
          compilerOpenApiSnippets.get().asFile.absolutePath,
          compilerOpenApiGeneratedDocument.get().asFile.absolutePath,
          compilerOpenApiGeneratedDocument.get().asFile.absolutePath,
          compilerOpenApiSupplementJson.get().asFile.absolutePath,
          compilerOpenApiDocument.get().asFile.absolutePath,
      )
      inputs.dir(compilerOpenApiSnippets)
      inputs.file(compilerOpenApiGeneratedDocument)
      outputs.files(compilerOpenApiSupplementJson, compilerOpenApiDocument)
    }

val compilerRepeatOpenApi3Supplement =
    tasks.register<JavaExec>("compilerRepeatOpenApi3Supplement") {
      description = "반복 생성 compiler OpenAPI에서 raw/multipart 본문 표현을 보완합니다."
      group = "verification"
      dependsOn(compilerRepeatOpenApi3, tasks.named("testClasses"))
      classpath = sourceSets.test.get().runtimeClasspath
      mainClass.set(
          "__SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestBodyOpenApiSupplementTestKt"
      )
      args(
          compilerRepeatSnippets.get().asFile.absolutePath,
          compilerRepeatOpenApiGeneratedDocument.get().asFile.absolutePath,
          compilerRepeatOpenApiGeneratedDocument.get().asFile.absolutePath,
          compilerRepeatOpenApiSupplementJson.get().asFile.absolutePath,
          compilerRepeatOpenApiDocument.get().asFile.absolutePath,
      )
      inputs.dir(compilerRepeatSnippets)
      inputs.file(compilerRepeatOpenApiGeneratedDocument)
      outputs.files(compilerRepeatOpenApiSupplementJson, compilerRepeatOpenApiDocument)
    }

afterEvaluate {
  tasks.named<com.epages.restdocs.apispec.gradle.OpenApi3Task>("openapi3") {
    outputDirectory = layout.buildDirectory.dir("api-spec/generated").get().asFile.path
    finalizedBy(openApi3Supplement)
  }
}

compilerRepeatOpenApi3.configure { finalizedBy(compilerRepeatOpenApi3Supplement) }

val springFixtureOpenApiSupplement =
    tasks.register<JavaExec>("springFixtureOpenApiSupplement") {
      description = "생성된 Spring fixture OpenAPI에서 raw/multipart 본문 표현을 보완합니다."
      group = "verification"
      dependsOn(springFixtureOpenApiJson, springFixtureOpenApiYaml, tasks.named("testClasses"))
      classpath = sourceSets.test.get().runtimeClasspath
      mainClass.set(
          "__SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.RequestBodyOpenApiSupplementTestKt"
      )
      args(
          springFixtureSnippets.get().asFile.absolutePath,
          springFixtureGeneratedOpenApiJson.get().file("openapi3.json").asFile.absolutePath,
          springFixtureGeneratedOpenApiYaml.get().file("openapi3.yaml").asFile.absolutePath,
          springFixtureOpenApiJsonOutput.asFile.absolutePath,
          springFixtureOpenApiYamlOutput.asFile.absolutePath,
      )
      inputs.dir(springFixtureSnippets)
      inputs.files(
          springFixtureGeneratedOpenApiJson.get().file("openapi3.json"),
          springFixtureGeneratedOpenApiYaml.get().file("openapi3.yaml"),
      )
      outputs.files(springFixtureOpenApiJsonOutput, springFixtureOpenApiYamlOutput)
    }

springFixtureOpenApiJson.configure { finalizedBy(springFixtureOpenApiSupplement) }

springFixtureOpenApiYaml.configure { finalizedBy(springFixtureOpenApiSupplement) }

tasks.withType<com.epages.restdocs.apispec.gradle.OpenApi3Task>().configureEach {
  dependsOn(compilerOpenApiTest)
  notCompatibleWithConfigurationCache(
      "restdocs-api-spec 0.20.1의 OpenApi3Task는 Jackson 상태를 직렬화할 수 없습니다."
  )
}

val openApiTestSourceSet = sourceSets.create("openApiTest")

openApiTestSourceSet.compileClasspath += sourceSets.main.get().output

openApiTestSourceSet.runtimeClasspath += sourceSets.main.get().output

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
      dependsOn(
          tasks.withType<com.epages.restdocs.apispec.gradle.OpenApi3Task>(),
          springFixtureOpenApiSupplement,
          openApi3Supplement,
          compilerRepeatOpenApi3Supplement,
      )
      inputs.files(
          compilerOpenApiDocument,
          compilerRepeatOpenApiDocument,
          springFixtureOpenApiJsonOutput,
          springFixtureOpenApiYamlOutput,
      )
      useJUnitPlatform()
    }

tasks.named("build") {
  dependsOn(
      springFixtureOpenApiJson,
      springFixtureOpenApiYaml,
      springFixtureOpenApiSupplement,
      openApiTest,
  )
}
