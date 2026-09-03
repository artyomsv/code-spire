// spire-agent-image: the conformance checker for the published agent-image contract (FR-F13).
//
// Apache-2.0, deliberately. The contract is published for ANY image to satisfy
// (docs/factory/AGENT-IMAGE-CONTRACT.md), and a contract whose checker a third
// party may not run is not much of a contract. It depends on no service module,
// which is the ADR-021 invariant.
plugins {
    java
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The same client the reference runtime arm uses, at the same version: a
    // checker that talked to the daemon differently from the thing it verifies
    // for would be checking its own beliefs.
    implementation("com.github.docker-java:docker-java-core:3.5.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.5.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "spire-agent-image"
    mainClass = "dev.codespire.agentimage.AgentImageCli"
}

tasks.test {
    useJUnitPlatform()
    // Handed in explicitly rather than guessed from the working directory, so the reference-image
    // check builds the same entrypoint from Gradle and from an IDE. The same reasoning, and the
    // same property name, as spire-arch.
    systemProperty("spire.repoRoot", rootProject.projectDir.absolutePath)
}
