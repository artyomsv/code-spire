// Phase 0: the single-process app wiring ALL pipeline modules over the
// SmallRye in-memory connector (ADR-008 build sequencing). At Phase 1 the
// gateway / review-worker / context-worker split into their own deployables
// over Redpanda — same ports, wiring change only.
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
    implementation(project(":spire-diff"))
    implementation(project(":spire-scm-bitbucket"))
    implementation(project(":spire-llm"))

    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-websockets-next")
    implementation("io.quarkus:quarkus-messaging")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.wiremock:wiremock:3.9.1")
}

tasks.test {
    useJUnitPlatform()
}
