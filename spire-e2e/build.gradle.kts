// Drives a RUNNING packaged stack from outside: HTTP to the dashboard's nginx, HTTP to a
// containerised GitLab, and `docker compose exec postgres psql` for the read model.
//
// Depends on NO module in this repo, deliberately. Two reasons, and both matter: LICENSING.md forbids
// an Apache-2.0 module depending on a service module, and a harness that can import our types can
// assert things no operator could observe — which is exactly the self-confirming test this suite
// exists to replace.
// `java` names the Gradle extension inside this script, which shadows the java.* package — so the
// Duration below has to arrive by import rather than fully qualified.
import java.time.Duration

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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
}

tasks.test {
    useJUnitPlatform()

    // A scenario waits on real webhook delivery and two model calls per round. The default (no
    // timeout) would hang a nightly job forever on a wedged stack; Await's own per-step deadlines are
    // the real bound and are much tighter.
    timeout = Duration.ofMinutes(45)

    // Never cache a pass. The inputs to these tests are a running stack and a live GitLab, neither of
    // which Gradle can see, so an up-to-date check here would report a cached green against a stack
    // that has since changed underneath it.
    outputs.upToDateWhen { false }

    // Psql runs `docker compose` with repo-relative -f paths, so it needs the root explicitly rather
    // than guessing from the working directory — which differs between Gradle and an IDE run.
    systemProperty("spire.repoRoot", rootProject.projectDir.absolutePath)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
