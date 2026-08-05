import java.util.concurrent.TimeUnit

allprojects {
    group = "dev.codespire"
    version = "0.1.0-SNAPSHOT"
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
 * The two CI test tiers, split by whether a module's tests need Docker.
 *
 * `fastTestModules` run on a bare JVM. The three deployables in `serviceTestModules` boot Postgres
 * and Kafka through Quarkus Dev Services and are the slow half of the suite, so CI runs them as their
 * own job rather than making a typo fix wait behind them.
 *
 * Declared here rather than as a list of module paths inside a workflow file for two reasons. It stays
 * runnable locally — `./gradlew testFast` is the pre-commit loop — and `TestTierCoverageTest` in
 * spire-arch can read it, so a module that joins neither tier fails the build instead of being
 * silently tested by nothing while CI reports green.
 */
val fastTestModules = listOf(
    "spire-contract",
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
)

val serviceTestModules = listOf(
    "spire-gateway",
    "spire-orchestrator",
    "spire-review-worker",
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
