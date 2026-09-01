import java.util.concurrent.TimeUnit

allprojects {
    group = "dev.codespire"
    version = "0.1.0-SNAPSHOT"
}

/**
 * Versions forced ahead of the Quarkus platform because the platform's choice carries a published
 * advisory and no platform release yet contains the fix.
 *
 * Most of what the 2026-08 Trivy sweep flagged is not here, because the better lever was the platform
 * itself: 3.37.1 -> 3.38.1 closes netty (CVE-2026-55831 and eight siblings), PostgreSQL
 * (CVE-2026-54291) and OpenTelemetry (CVE-2026-45292). Each of those ships as a stack whose modules
 * must move together — thirty-odd netty artifacts, the whole `io.opentelemetry` group — and Quarkus
 * imports its BOM with `enforcedPlatform`, so overriding one coordinate by hand means overriding all
 * of them and hoping the combination was tested. Upstream already did that work and published it as
 * a platform release.
 *
 * Two have no such release yet:
 *
 *  - `com.fasterxml.jackson.core` 2.22.0 -> 2.22.1 (CVE-2026-54515, CVE-2026-59889)
 *  - `at.yawk.lz4:lz4-java` 1.10.1 -> 1.11.1 (CVE-2026-59949)
 *
 * jackson-core moves with jackson-databind because a Jackson patch publishes them as a pair.
 * jackson-annotations does not: it versions only on a minor, and 2.22 is current.
 *
 * Remove an entry once the platform catches up. A force does not go quiet when it becomes
 * unnecessary — it starts pinning the version DOWN. `./gradlew :spire-orchestrator:dependencyInsight
 * --configuration runtimeClasspath --dependency <name>` shows what actually resolved.
 */
val advisoryOverrides = mapOf(
    "com.fasterxml.jackson.core:jackson-core" to "2.22.1",
    "com.fasterxml.jackson.core:jackson-databind" to "2.22.1",
    "at.yawk.lz4:lz4-java" to "1.11.1",
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            advisoryOverrides["${requested.group}:${requested.name}"]?.let { fixed ->
                useVersion(fixed)
                because("published advisory against the version the Quarkus platform selects")
            }
        }
    }
}

/**
 * Reaps the throwaway Postgres containers Quarkus Dev Services boots for `@QuarkusTest`.
 *
 * Nothing else does. Those containers are labelled `io.quarkus.devservice.launch-mode=TEST` but
 * carry no `org.testcontainers.sessionId`, so Testcontainers' Ryuk reaper — the sidecar that kills
 * containers when the test JVM's socket drops — does not own them, and they are created with
 * `AutoRemove=false`, so Docker will not drop them on stop either. Removal depends entirely on an
 * in-JVM shutdown that does not survive a killed daemon or a cancelled run. Left alone they
 * accumulate roughly one per test JVM per build.
 *
 * The snapshot is taken when the first test task starts and is exact here: every `@QuarkusTest`
 * boots its Dev Services inside the test JVM, i.e. strictly after that point, so everything this
 * build created shows up in the diff. Containers that already existed are never touched — neither a
 * concurrent build's nor a `quarkusDev` session's, the latter being labelled DEV rather than TEST.
 * If the snapshot were ever taken late the container would be left behind rather than a stranger's
 * killed: the failure direction is a leak, not a broken run.
 */
abstract class DevServicesReaper : BuildService<BuildServiceParameters.None>, AutoCloseable {

    private val preexisting: Set<String> = testModeContainers()

    override fun close() {
        remove(testModeContainers() - preexisting)
    }

    companion object {

        private const val TEST_MODE_LABEL = "io.quarkus.devservice.launch-mode=TEST"

        private val logger = Logging.getLogger(DevServicesReaper::class.java)

        fun testModeContainers(): Set<String> =
            docker(listOf("ps", "--all", "--quiet", "--filter", "label=$TEST_MODE_LABEL"))
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()

        /** `--volumes` matters: each container owns an anonymous volume that otherwise orphans. */
        fun remove(containerIds: Collection<String>) {
            if (containerIds.isEmpty()) return
            docker(listOf("rm", "--force", "--volumes") + containerIds)
            logger.lifecycle("Removed ${containerIds.size} Quarkus Dev Services container(s).")
        }

        /**
         * Never fails the build. No Docker on the PATH is a normal state for a source-only build,
         * and a reaper that breaks `./gradlew compileJava` would be worse than the leak it fixes.
         */
        private fun docker(args: List<String>): String =
            try {
                val process = ProcessBuilder(listOf("docker") + args)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor(60, TimeUnit.SECONDS)) output else "".also { process.destroyForcibly() }
            } catch (e: Exception) {
                logger.info("Dev Services reaper: `docker ${args.first()}` unavailable — $e")
                ""
            }
    }
}

val devServicesReaper =
    gradle.sharedServices.registerIfAbsent("devServicesReaper", DevServicesReaper::class) {}

subprojects {
    tasks.withType<Test>().configureEach {
        usesService(devServicesReaper)
        // Force instantiation, which is what takes the snapshot: a service Gradle never creates is
        // a service Gradle never closes.
        doFirst { devServicesReaper.get() }
    }
}

/** Sweeps leftovers a killed build never got to reap. Safe while no test JVM is running. */
tasks.register("cleanDevServices") {
    group = "build"
    description = "Removes every leftover Quarkus Dev Services test container and its volume."
    doLast { DevServicesReaper.remove(DevServicesReaper.testModeContainers()) }
}

/**
 * The three CI test tiers, split by what a module's tests OWN.
 *
 * `fastTestModules` run on a bare JVM. `serviceTestModules` is defined by what its tests OWN, not by
 * what they are: a module belongs here when its tests BOOT the thing they talk to. For the three
 * deployables that is Postgres and Kafka through Quarkus Dev Services; for `spire-runtime-docker` it
 * is the containers it creates on a real daemon, whose behaviour — whether a read-only bind is
 * actually read-only — is precisely what a fake would get wrong. Either way they need Docker and are
 * the slow half of the suite, so CI runs them as their own job rather than making a typo fix wait.
 *
 * `e2eTestModules` is not simply "even slower". A service test BOOTS what it talks to, so it is
 * hermetic and belongs on the PR path; an e2e test is HANDED a running stack and a containerised
 * GitLab, so it can only run where one exists. That is the line between the second tier and the
 * third, and it is why the third is nightly.
 *
 * Declared here rather than as a list of module paths inside a workflow file for two reasons. It stays
 * runnable locally — `./gradlew testFast` is the pre-commit loop — and `TestTierCoverageTest` in
 * spire-arch can read it, so a module that joins neither tier fails the build instead of being
 * silently tested by nothing while CI reports green.
 */
val fastTestModules = listOf(
    "spire-contract",
    // Its tests boot nothing: the git library is exercised against a temporary on-disk repository,
    // which is a bare JVM by the rule this list states. It was in the service tier by proximity to
    // the run unit it ships inside, which is not the criterion.
    "spire-publisher",
    "spire-arch",
    "spire-encryption",
    "spire-diff",
    "spire-http",
    "spire-llm",
    "spire-scm-bitbucket",
    "spire-scm-github",
    "spire-scm-gitlab",
    "spire-context-jira",
    "spire-context-confluence",
    "spire-context-github",
    "spire-context-gitlab",
    "spire-context-code",
    "spire-harness",
    "spire-harness-codex",
    "spire-workspace",
    "spire-runtime",
)

val serviceTestModules = listOf(
    "spire-runtime-docker",
    "spire-run-worker",
    "spire-gateway",
    "spire-orchestrator",
    "spire-review-worker",
)

/**
 * Modules whose tests drive a RUNNING packaged stack (deploy/compose.yml plus deploy/compose.e2e.yml)
 * rather than booting their own dependencies. They start nothing: a stack that is not up is a fast,
 * loud failure, not a five-minute wait behind a GitLab boot.
 *
 * CI runs this tier nightly, never on the PR path — see .github/workflows/e2e.yml and
 * docs/superpowers/specs/2026-08-29-gitlab-e2e-suite-design.md.
 */
val e2eTestModules = listOf(
    "spire-e2e",
)

tasks.register("testFast") {
    group = "verification"
    description = "Runs every module whose tests need no Docker. The fast half of CI."
    dependsOn(fastTestModules.map { ":$it:test" })
}

tasks.register("testServices") {
    group = "verification"
    description = "Runs the three deployables' tests (Quarkus Dev Services: Postgres + Kafka)."
    dependsOn(serviceTestModules.map { ":$it:test" })
}

tasks.register("testE2e") {
    group = "verification"
    description = "Drives a running packaged stack + containerised GitLab. Nightly; needs the stack up."
    dependsOn(e2eTestModules.map { ":$it:test" })
}
