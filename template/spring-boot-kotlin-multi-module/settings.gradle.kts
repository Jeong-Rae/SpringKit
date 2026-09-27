plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "__SPRINGKIT_PROJECT_NAME__"

include(
    "declarative-rest-docs",
    "__SPRINGKIT_ARTIFACT__-presentation",
    "__SPRINGKIT_ARTIFACT__-application",
    "__SPRINGKIT_ARTIFACT__-domain",
    "__SPRINGKIT_ARTIFACT__-infrastructure",
    "__SPRINGKIT_ARTIFACT__-batch",
    "__SPRINGKIT_ARTIFACT__-worker",
)
