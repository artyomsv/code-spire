// spire-workspace: the publisher's git library. Clone bare, fetch the agent's
// bundle, diff it against the base, push the gated sha. It runs in the
// PUBLISHER image, never in the worker — the worker holds no filesystem and
// runs no git at all (ADR-039, docs/factory/RUN-TOPOLOGY.md).
plugins {
    // java-library, so JGit can be an `api` dependency rather than `implementation`.
    // PublishRepo declares GitAPIException on three methods, so the type is part of this
    // module's public surface: a consumer cannot compile against it without JGit on its
    // own compile classpath. Declaring it `implementation` would compile here and fail
    // at every call site instead.
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
    // JGit rather than shelling out: the publisher image then needs no git binary, and
    // clone/fetch-bundle/diff/push are testable in-process against a local origin with no network.
    //
    // Newest published 7.x as of 2026-09-01, verified resolvable on Maven Central. The plan pinned
    // 7.1.0.202411261347-r, which also resolves — but it is nine months older, and this dependency
    // parses a bundle an agent wrote, so it is the last place to carry an avoidable lag.
    api("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
