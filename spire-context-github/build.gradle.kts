// GitHub issue context provider: resolves the issue, pull-request and cross-repo
// references in a PR's text into ContextItems for the review prompt (CONTRACT §7/§8).
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in the
// service that hosts it (spire-review-worker).
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
    implementation(project(":spire-http"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
