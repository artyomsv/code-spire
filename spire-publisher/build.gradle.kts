// spire-publisher: the sidecar that gates and pushes. Plain Java with a main —
// it runs for the length of one run and needs no framework.
//
// FSL, not Apache-2.0: this is a deployable, and it is the ONLY part of a run
// unit that holds a git write credential (ADR-021, ADR-039).
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
    implementation(project(":spire-workspace"))
    // The scrubber the failure lines go through; not via spire-workspace, so both callers depend on
    // the same JDK-only module rather than one inheriting the other's git library.
    implementation(project(":spire-secrets"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    // JGit logs through SLF4J 1.7. With no binding it prints a three-line warning to stderr at
    // every start, which the worker's log stream then carries for every run. The publisher's
    // reporting channel is its stdout JSON lines by design (ADR-039); a logger adds nothing.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.codespire.publisher.PublisherMain"
}

tasks.test {
    useJUnitPlatform()
}
