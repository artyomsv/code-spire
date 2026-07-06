// Encryption at rest (ADR-009 / ADR-015): a framework-free Tink AES-256-GCM
// wrapper shared by every KEK holder (orchestrator, UI-side, worker). CDI
// wiring (producing the CryptoService bean from config) happens in each host
// service — this module only owns the cipher and keyset handling.
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
    // Tink stays encapsulated here — the public API is byte[]/String only.
    implementation("com.google.crypto.tink:tink:1.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
