// spire-harness-claude-code: the HarnessAdapter arm driving Anthropic's Claude Code CLI non-interactively,
// for review runs only and only behind the run's model proxy (harness reviews 3b). An adapter, not a pure
// module, so it may parse JSON — spire-harness itself stays framework-free (docs/factory/MODULES.md §2).
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // HarnessAdapterContract: the rules every arm must obey, extended rather than restated.
    testImplementation(testFixtures(project(":spire-harness")))
    // TEST ONLY, as in spire-harness-codex: HarnessTokenReport's declaration for this arm is pinned here against
    // the adapter that does the reporting. No production dependency is added.
    testImplementation(project(":spire-contract"))
}

tasks.test {
    useJUnitPlatform()
}
