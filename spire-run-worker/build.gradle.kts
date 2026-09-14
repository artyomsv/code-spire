// spire-run-worker: consumes cs.run-commands, creates the three-container run
// unit, streams its two log channels, and publishes cs.run-results.
//
// It performs NO git and holds NO filesystem (ADR-039). That is what makes it
// stateless, and therefore what lets any replica salvage any run rather than
// only the one that started it.
plugins {
    java
    id("io.quarkus")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation(project(":spire-contract"))
    implementation(project(":spire-encryption")) // decrypt the per-command credentials (ADR-015)
    implementation(project(":spire-harness"))
    implementation(project(":spire-harness-codex"))
    implementation(project(":spire-runtime"))
    implementation(project(":spire-runtime-docker"))
    // SecretScrub: one credential scrubber, shared with the publisher. A module of its own and
    // not spire-workspace, which is where it first landed -- that module exposes JGit as api, so
    // depending on it put org.eclipse.jgit on this worker's classpath, and this worker runs no git
    // (ADR-039). spire-arch's RunWorkerRunsNoGitTest enforces that it takes NOTHING from
    // spire-workspace; spire-secrets carries the JDK and nothing else.
    implementation(project(":spire-secrets"))

    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-scheduler") // the lease heartbeat: a live run must not read as an orphan
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-oidc") // operator auth on the HTTP surface, as every deployable (ADR-022)
    implementation("io.quarkus:quarkus-rest-jackson") // the /rw/auth session endpoints every deployable exposes
    implementation("io.quarkus:quarkus-logging-json") // structured JSON logs in prod

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-test-security")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
}

// quarkusDev runs with the module dir as CWD, but the single dev-env .env lives
// at the repo root -- point dev mode there so ${POSTGRES_*} et al. resolve.
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    workingDirectory.set(rootProject.projectDir)
}

tasks.test {
    useJUnitPlatform()
    // Fixed child-JVM commands resolve the same JDK selected for this Test task.
    val pathVariable = System.getenv().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    environment(pathVariable, javaLauncher.get().executablePath.asFile.parent + System.getProperty("path.separator") + System.getenv(pathVariable))
    // M0WalkingSkeletonTest builds spire-publisher:latest from the installed distribution and the
    // two test images from src/test/docker, so it needs the distribution and the repository root.
    dependsOn(":spire-publisher:installDist")
    // A separate plain JVM consumes actual worker results with the production charge ledger.
    // This is test execution wiring, not an application dependency between deployables.
    dependsOn(":spire-orchestrator:testClasses")
    doFirst {
        val classpathFile = layout.buildDirectory.file("test-support/orchestrator-classpath.txt").get().asFile
        classpathFile.parentFile.mkdirs()
        classpathFile.writeText(project(":spire-orchestrator")
            .extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath.asPath)
        systemProperty("spire.orchestratorTestClasspathFile", classpathFile.absolutePath)
        val workerClasspath = layout.buildDirectory.file("test-support/worker-classpath.txt").get().asFile
        workerClasspath.writeText(sourceSets["test"].runtimeClasspath.asPath)
        systemProperty("spire.workerTestClasspathFile", workerClasspath.absolutePath)
    }
    systemProperty("spire.repoRoot", rootDir.absolutePath)
}
