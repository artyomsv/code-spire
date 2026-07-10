// spire-gateway: the ONE synchronous edge. Verifies webhooks, translates them
// into integration events, publishes to cs.integration, returns 202
// (ARCHITECTURE §3, TECH-STACK §1). First deployable of the P1 service split.
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
    implementation(project(":spire-scm-bitbucket"))
    implementation(project(":spire-scm-github"))
    implementation(project(":spire-encryption")) // decrypts per-repo webhook secrets (webhook keyset only)

    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-logging-json") // structured JSON logs in prod (plain console in dev/test)
    // The gateway owns its webhook registry (its own schema + migrations).
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-flyway")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
    testImplementation("io.rest-assured:rest-assured")
}

tasks.test {
    useJUnitPlatform()
}

// quarkusDev runs with the module dir as CWD, but the single dev-env .env lives
// at the repo root — point dev mode there so ${POSTGRES_*} et al. resolve.
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    workingDirectory.set(rootProject.projectDir)
}
