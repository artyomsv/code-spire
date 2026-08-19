// Shared read-only JSON-over-HTTP client for the context adapters: one home for the host-pinned
// manual redirect handling and the private-address (SSRF) guard, so a fix to either lands once.
// Framework-free and domain-free — depends on Jackson and nothing in this repo. The three SCM
// clients still carry their own copy of this logic (techdebt/global/); this module is not yet
// their transport too.
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
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
