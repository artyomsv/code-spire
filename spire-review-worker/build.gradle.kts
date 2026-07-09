// spire-review-worker: consumes cs.commands, performs the side-effecting work
// (diff fetch, context stub, LLM review, idempotent comment posting) and
// publishes cs.results (TECH-STACK §1). Owns the comment_idempotency table in
// its own schema (DATA-MODEL §5, schema-per-service).
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
    implementation(project(":spire-encryption")) // decrypt the per-command SCM credential (ADR-015)
    implementation(project(":spire-diff"))
    implementation(project(":spire-scm-bitbucket"))
    implementation(project(":spire-scm-github"))
    implementation(project(":spire-scm-gitlab"))
    implementation(project(":spire-context-jira"))
    implementation(project(":spire-context-confluence"))
    implementation(project(":spire-llm"))

    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-logging-json") // structured JSON logs in prod (plain console in dev/test)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
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
