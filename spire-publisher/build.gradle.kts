// spire-publisher: the sidecar that gates and pushes. Plain Java with a main —
// it runs for the length of one run and needs no framework.
//
// FSL, not Apache-2.0: this is a deployable, and it is the ONLY part of a run
// unit that holds a git write credential (ADR-021, ADR-038).
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.codespire.publisher.PublisherMain"
}

tasks.test {
    useJUnitPlatform()
}
