// Pure domain library: events, commands, value types, ports, and the
// ReviewLifecycle decider. NO infrastructure dependencies (no Quarkus) —
// deciders/views/sagas are pure functions, unit-tested without a runtime.
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
    // Annotations only (no databind): the sealed event/command hierarchies ARE
    // the wire contract, so the polymorphic type ids live with them.
    api("com.fasterxml.jackson.core:jackson-annotations:2.22")

    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
