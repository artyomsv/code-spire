// Confluence context provider: resolves the Confluence page links referenced in
// the PR into ContextItems for the review prompt (CONTRACT §7/§8 — the second
// ContextProvider on the same SPI as spire-context-jira, link-driven instead of
// ticket-key-driven per EVENT-MODEL S4).
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in the
// service that hosts it (spire-review-worker).
plugins {
    `java-library`
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
    api(project(":spire-contract"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
