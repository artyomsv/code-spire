// One credential scrubber, for every process that writes a failure message a human will read.
//
// It lives in a module of its own rather than beside either caller because the two callers share
// nothing else, and because of what a shared module DRAGS. The scrubber first landed in
// spire-workspace — which the publisher already depended on — and that put `org.eclipse.jgit` on the
// run worker's compile AND runtime classpath, since spire-workspace exposes JGit as `api` (its
// PublishRepo throws GitAPIException). The worker's whole architectural claim is that it runs no git
// (ADR-039); a source scan can refuse an IMPORT, but it cannot refuse a capability that is merely
// present. spire-http was extracted for the same reason one level down: one home for a guard,
// carried by nothing else.
//
// So: the JDK and nothing at all, which is what lets both an FSL service and an Apache library
// depend on it without either inheriting the other's world.
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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
