// GitHub adapters: DiffSource, CommentSink (and later ScmIngress) — the
// SCM-MAPPING.md §GitHub column made real. Framework-free library (JDK
// HttpClient + Jackson); CDI wiring happens in the service that hosts it.
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
    implementation(project(":spire-diff"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
