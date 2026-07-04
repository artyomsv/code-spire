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
include("spire-diff")
include("spire-scm-bitbucket")
include("spire-llm")
include("spire-gateway")
include("spire-review-worker")
include("spire-orchestrator")
