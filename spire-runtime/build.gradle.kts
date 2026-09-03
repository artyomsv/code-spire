// spire-runtime: the run-placement SPI. Framework-free by rule — the JDK and
// this module only. A runtime places a three-container run unit somewhere and
// controls its life; what runs inside is the harness adapter's business
// (docs/factory/MODULES.md, docs/factory/RUN-TOPOLOGY.md §3).
plugins {
    java
    // RunRuntimeContract -- the rules every arm must obey, executable rather than prose -- is
    // published from src/testFixtures so a second arm extends one copy instead of restating them.
    // Test-only: it puts JUnit on no consumer's runtime classpath, and the framework-free rule
    // applies to src/main/java. The harness SPI does exactly this for the same reason.
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
    testFixturesApi(platform("org.junit:junit-bom:6.1.3"))
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
