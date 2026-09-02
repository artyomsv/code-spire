// spire-run-worker: consumes cs.run-commands, creates the three-container run
// unit, streams its two log channels, and publishes cs.run-results.
//
// It performs NO git and holds NO filesystem (ADR-038). That is what makes it
// stateless, and therefore what lets any replica salvage any run rather than
// only the one that started it.
plugins {
    java
    id("io.quarkus")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation(project(":spire-contract"))
    implementation(project(":spire-encryption")) // decrypt the per-command credentials (ADR-015)
    implementation(project(":spire-harness"))
    implementation(project(":spire-harness-codex"))
    implementation(project(":spire-runtime"))
    implementation(project(":spire-runtime-docker"))

    // NOTE what is absent: spire-workspace. This worker runs no git. If that dependency ever
    // appears here, the statelessness ADR-038 rests on has been lost — a worker with a clone has a
    // filesystem, and a run then belongs to the replica that started it.

    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-oidc") // operator auth on the HTTP surface, as every deployable (ADR-022)
    implementation("io.quarkus:quarkus-rest-jackson") // the /rw/auth session endpoints every deployable exposes
    implementation("io.quarkus:quarkus-logging-json") // structured JSON logs in prod

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-security")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
}

tasks.test {
    useJUnitPlatform()
    // M0WalkingSkeletonTest builds spire-publisher:latest from the installed distribution and the
    // two test images from src/test/docker, so it needs the distribution and the repository root.
    dependsOn(":spire-publisher:installDist")
    systemProperty("spire.repoRoot", rootDir.absolutePath)
}
