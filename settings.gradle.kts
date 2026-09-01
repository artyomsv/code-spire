pluginManagement {
    val quarkusPluginVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusPluginVersion
    }
}

rootProject.name = "code-spire"

include("spire-contract")
include("spire-arch")
include("spire-encryption")
include("spire-diff")
include("spire-scm-bitbucket")
include("spire-scm-github")
include("spire-scm-gitlab")
include("spire-http")
include("spire-context-jira")
include("spire-context-confluence")
include("spire-context-github")
include("spire-context-gitlab")
include("spire-context-code")
include("spire-llm")
include("spire-harness")
include("spire-gateway")
include("spire-review-worker")
include("spire-orchestrator")

include("spire-e2e")
