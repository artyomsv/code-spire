// Jira context provider: resolves the PR's referenced issue keys into
// ContextItems for the review prompt (CONTRACT §7/§8 — the ContextProvider port
// made real, the SCM-adapter pattern applied to a context source).
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
