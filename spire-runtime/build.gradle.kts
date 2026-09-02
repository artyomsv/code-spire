// spire-runtime: the run-placement SPI. Framework-free by rule — the JDK and
// this module only. A runtime places a three-container run unit somewhere and
// controls its life; what runs inside is the harness adapter's business
// (docs/factory/MODULES.md, docs/factory/RUN-TOPOLOGY.md §3).
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
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
