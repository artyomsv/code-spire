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

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // HarnessAdapterContract: the rules every arm must obey, extended rather than restated.
    testImplementation(testFixtures(project(":spire-harness")))
}

tasks.test {
    useJUnitPlatform()
}
