// Bitbucket Cloud adapters: ScmIngress (webhook verify + translate),
// DiffSource, CommentSink — the SCM-MAPPING.md §Bitbucket column made real.
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in
// the service that hosts it.
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.9.1")
}

tasks.test {
    useJUnitPlatform()
}
