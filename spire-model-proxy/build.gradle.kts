// spire-model-proxy: the run unit's model proxy. The agent sends plain HTTP with a placeholder
// credential; this process drops every credential header, injects the run's real one, and forwards
// to the vendor over HTTPS. Plain JDK with a main (HttpServer + HttpClient): it runs for the length
// of one run and needs no framework and no dependency.
//
// FSL, not Apache-2.0: this is a deployable, and inside a review run unit it is the ONLY container
// holding the model credential (ADR-021).
plugins {
    java
    application
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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.codespire.modelproxy.ModelProxy"
}

tasks.test {
    useJUnitPlatform()
}
