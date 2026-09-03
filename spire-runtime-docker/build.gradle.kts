// spire-runtime-docker: the first RunRuntime arm. Docker has no pods, so this
// module builds the three-container run unit by hand over named volumes
// (docs/factory/RUN-TOPOLOGY.md §3). Its tests drive a real daemon.
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
    implementation(project(":spire-runtime"))
    // Newest published 3.5.x as of 2026-09-01, verified resolvable on Maven Central. The plan
    // pinned 3.4.1, which also resolves; this arm talks to the daemon that runs untrusted agent
    // code, so it is not a place to carry an avoidable lag.
    implementation("com.github.docker-java:docker-java-core:3.7.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
