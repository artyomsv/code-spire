// spire-harness-codex: the first HarnessAdapter arm, driving OpenAI Codex CLI
// non-interactively. An adapter, not a pure module, so it may parse JSON —
// spire-harness itself stays framework-free (docs/factory/MODULES.md §2).
plugins {
    java
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
    implementation(project(":spire-harness"))
    // Jackson databind only: this module is an adapter, not a pure module, so it may parse JSON.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // HarnessAdapterContract: the rules every arm must obey, extended rather than restated.
    testImplementation(testFixtures(project(":spire-harness")))
    // TEST ONLY, for the same reason spire-harness takes it that way: HarnessTokenReport declares what
    // this arm can report so the orchestrator can refuse a model it cannot price, and the declaration
    // is pinned here against the adapter that does the reporting. No production dependency is added.
    testImplementation(project(":spire-contract"))
}

tasks.test {
    useJUnitPlatform()
}
