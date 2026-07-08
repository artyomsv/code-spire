// spire-orchestrator: the domain brain after the P1 split (TECH-STACK §1) —
// ReviewLifecycle decider + sagas, OWNS the event store, drives the pipeline
// over cs.* topics, and serves the operator timeline dashboard.
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
    implementation(project(":spire-encryption")) // AES-GCM encryption at rest (ADR-009 / ADR-015)
    implementation(project(":spire-scm-bitbucket")) // read-only: fetch PR metadata for manual register
    implementation(project(":spire-scm-github")) // read-only: fetch PR metadata for manual register
    implementation(project(":spire-scm-gitlab")) // read-only: fetch PR metadata for manual register

    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-websockets-next")
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-logging-json") // structured JSON logs in prod (plain console in dev/test)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}

// quarkusDev runs with the module dir as CWD, but the single dev-env .env lives
// at the repo root — point dev mode there so ${POSTGRES_*} et al. resolve.
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    workingDirectory.set(rootProject.projectDir)
}
