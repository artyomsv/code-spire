// spire-harness: the agent-execution SPI. Framework-free by rule — the JDK and
// this module only. An adapter turns an invocation into argv plus environment
// and one line of the tool's output into one normalized event; sandboxes,
// credentials, retry and cost belong to the worker (docs/factory/MODULES.md §2).
plugins {
    java
    // HarnessAdapterContract — the rules every arm must obey, executable rather than prose —
    // is published from src/testFixtures so each spire-harness-* module extends one copy instead
    // of restating them. Test-only: it puts JUnit on no consumer's runtime classpath, and the
    // framework-free rule applies to src/main/java, which PureModulesAreFrameworkFreeTest scans.
    `java-test-fixtures`
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
    testFixturesApi(platform("org.junit:junit-bom:5.11.4"))
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
