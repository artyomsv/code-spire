package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three test tasks drive the one real Docker daemon, and the build runs modules in parallel.
 *
 * <p>{@code org.gradle.parallel=true} plus a {@code testServices} that depends on five modules means
 * {@code spire-runtime-docker} and {@code spire-run-worker} create, inspect and destroy containers
 * against the same daemon at the same time. Observed on this branch's predecessor:
 * {@code DockerRunRuntimeIT} failed two cases under {@code testServices} and passed 15 of 15 run
 * alone, twice.
 *
 * <p><b>The expensive part is not the flake, it is what the flake looks like.</b> The two failures
 * were "no such container" on an inspect and "destroying it must actually remove it" — the exact
 * symptoms the runtime is written to prevent, so the natural reading is that container lifecycle
 * broke. A red that impersonates a real defect costs more than a red that announces itself.
 *
 * <p>So the daemon is a Gradle shared build service with {@code maxParallelUsages = 1}: the two
 * tasks queue behind each other while the other service modules still run in parallel. That second
 * half is the reason this is a service rather than switching {@code org.gradle.parallel} off, and
 * both halves are asserted below — including, against {@code gradle.properties}, that parallelism is
 * still on at all. Without that last assertion the cheap fix passes every test here, which is
 * exactly what an earlier version of this class claimed to prevent while not doing so.
 *
 * <p>Which modules need the lock is <b>derived, not trusted</b>. Reading the declared list and
 * checking it against itself would pass forever; instead the test scans test sources for daemon use
 * and requires the declaration to match. A module that starts driving Docker and forgets to declare
 * itself fails here rather than months later as an unexplained flake. That paid for itself
 * immediately: the plan for this change named two modules and the scan found three, because
 * {@code spire-e2e} shells out to {@code docker compose} for its psql and Rails probes. It sits in
 * the nightly tier, so it never meets {@code testServices} in CI and the lock costs it nothing — but
 * the lock is about the daemon, not about a tier, and a local run of both tiers is exactly the case
 * a tier-shaped rule would miss.
 *
 * <p><b>Two bounds, stated rather than implied.</b> The lock is per Gradle invocation: a second
 * {@code ./gradlew}, a {@code quarkusDev} run worker, or the packaged stack on the same daemon still
 * contends, and this check says nothing about those. And it is held for a whole {@code Test} task
 * rather than the one suite inside it that needs the daemon, because Gradle's unit of scheduling is
 * the task; buying that back would mean splitting the Docker-driving suites into their own tasks,
 * which costs another tier entry for a saving that has not been measured to matter.
 */
class DockerTestsAreSerialisedTest {

    /** The Gradle shared service that serialises them, and the property that makes it serialise. */
    private static final String LOCK_SERVICE = "dockerDaemonLock";

    /**
     * The lock's own registration block. Anchored, because {@code maxParallelUsages} matched
     * file-wide takes the FIRST occurrence anywhere: register a second shared service above this one
     * and the limit asserted below would be that service's, while this lock is free to drift. Same
     * shape as the nginx guard that was satisfied by a directive in the wrong scope.
     */
    private static final Pattern LOCK_REGISTRATION = Pattern.compile(
            "registerIfAbsent\\(\\s*\"" + LOCK_SERVICE + "\"[^{]*\\{([^}]*)}");

    private static final Pattern MAX_PARALLEL =
            Pattern.compile("maxParallelUsages\\.set\\((\\d+)\\)|maxParallelUsages\\s*=\\s*(\\d+)");

    /**
     * The lock applied ONLY to the declared modules. A bare {@code contains} of the call would pass
     * with the surrounding {@code if} deleted, and every test task in the repository would then
     * serialise — the slow outcome this whole design exists to avoid, reached by the one edit the
     * other assertions cannot see.
     */
    private static final Pattern GUARDED_USE = Pattern.compile(
            "if\\s*\\(\\s*project\\.name\\s+in\\s+dockerDrivingModules\\s*\\)\\s*\\{\\s*usesService\\(\\s*"
                    + LOCK_SERVICE + "\\s*\\)");

    /** Parallelism itself, which lives in gradle.properties and not in the build script. */
    private static final Pattern PARALLEL_ON =
            Pattern.compile("^\\s*org\\.gradle\\.parallel\\s*=\\s*true", Pattern.MULTILINE);

    /**
     * Daemon use, in the forms this repository actually writes it: the client library, the real
     * runtime arm constructed in a test, and the CLI named as a literal. Matched against
     * comment-stripped source, because a test that merely mentions {@code docker inspect} in a
     * comment explaining a security property is not a test that talks to the daemon.
     *
     * <p>The CLI marker allows a space after the name as well as a closing quote. Argv is built two
     * ways here — {@code argv.add("docker")} in one module, {@code List.of("docker", "compose", …)}
     * in another, and a single {@code "docker compose -f …"} string in a third — and a pattern
     * requiring the closing quote missed that third form, which already exists in this repository.
     *
     * <p><b>Known blind spot, on purpose:</b> a test driving the daemon through Testcontainers is
     * invisible here. Quarkus Dev Services use Testcontainers, so making it a marker would flag every
     * deployable and the derived list would lose its meaning. A future container-lifecycle test
     * written on Testcontainers gets no signal from this scan.
     */
    private static final List<Pattern> DAEMON_USE = List.of(
            Pattern.compile("com\\.github\\.dockerjava"),
            Pattern.compile("new\\s+DockerRunRuntime\\s*\\("),
            Pattern.compile("\"docker[ \"]"));

    /**
     * The scanner's own module. Every pattern above appears in this file as a literal, so including
     * it would classify the module that owns the check as a daemon driver. spire-arch reads text and
     * boots nothing, which is why the exclusion is safe rather than convenient.
     */
    private static final String SCANNER_MODULE = "spire-arch";

    @Test
    void everyModuleWhoseTestsDriveTheDaemonIsDeclared() throws IOException {
        Set<String> observed = modulesWhoseTestsDriveTheDaemon();
        Set<String> declared = new TreeSet<>(RootBuild.declaredList("dockerDrivingModules"));

        assertFalse(observed.isEmpty(),
                "the scan found no module driving the daemon, so it is asserting nothing — "
                        + "the markers no longer match how this repository talks to Docker");
        assertEquals(observed, declared,
                "modules whose tests drive the Docker daemon must be declared in the root build so "
                        + "they queue behind one another. Observed " + observed + ", declared " + declared);
    }

    @Test
    void theDeclaredModulesShareALockThatAllowsOneAtATime() throws IOException {
        String build = rootBuild();

        Matcher registration = LOCK_REGISTRATION.matcher(build);
        assertTrue(registration.find(),
                "the root build registers no " + LOCK_SERVICE + " shared service");
        Matcher limit = MAX_PARALLEL.matcher(registration.group(1));
        assertTrue(limit.find(), LOCK_SERVICE + " sets no maxParallelUsages, so it serialises nothing");
        String value = limit.group(1) != null ? limit.group(1) : limit.group(2);
        assertEquals("1", value,
                "maxParallelUsages must be 1; anything higher lets the two tasks meet on the daemon again");

        assertTrue(GUARDED_USE.matcher(build).find(),
                LOCK_SERVICE + " must be applied only to the modules in dockerDrivingModules. Applied "
                        + "unconditionally it serialises every test task in the repository, which is the "
                        + "slow fix this shared service exists to avoid");
    }

    @Test
    void theOtherServiceModulesAreNotSerialised() throws IOException {
        Set<String> declared = new TreeSet<>(RootBuild.declaredList("dockerDrivingModules"));
        Set<String> stillParallel = new TreeSet<>(RootBuild.declaredList("serviceTestModules"));
        stillParallel.removeAll(declared);

        // Derived from serviceTestModules rather than a hardcoded three, so a renamed or added
        // service module cannot quietly fall outside what this asserts.
        assertFalse(stillParallel.isEmpty(),
                "every service module is serialised, which is org.gradle.parallel=false by another "
                        + "name — the shared service exists so the modules that do not touch the daemon "
                        + "keep running alongside everything else");

        // The claim above only means something while the build is parallel at all, and that switch
        // lives in gradle.properties, which no other assertion here reads. Without this, turning
        // parallelism off — the cheap fix this design rejects — passes every test in this class.
        assertTrue(PARALLEL_ON.matcher(RootBuild.read("gradle.properties")).find(),
                "org.gradle.parallel is not true, so the lock is serialising a build that was already "
                        + "serial, and the cost this shared service exists to avoid has been paid anyway");
    }

    private static Set<String> modulesWhoseTestsDriveTheDaemon() throws IOException {
        Set<String> driving = new TreeSet<>();
        try (Stream<Path> modules = Files.list(RootBuild.repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                String name = module.getFileName().toString();
                if (!name.startsWith("spire-") || name.equals(SCANNER_MODULE)) {
                    continue;
                }
                // src/test/java, not src/test: the latter also walks test RESOURCES, and this
                // repository keeps real .java fixtures under them. A fixture is compiled by nothing
                // and can drive no daemon.
                Path tests = module.resolve("src/test/java");
                if (Files.isDirectory(tests) && anyTestDrivesTheDaemon(tests)) {
                    driving.add(name);
                }
            }
        }
        return driving;
    }

    private static boolean anyTestDrivesTheDaemon(Path tests) throws IOException {
        try (Stream<Path> sources = Files.walk(tests)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = JavaSource.withoutComments(Files.readString(source));
                if (DAEMON_USE.stream().anyMatch(p -> p.matcher(body).find())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Comment-stripped, like the Java scan. Read raw, a commented-out {@code usesService(...)} — an
     * ordinary thing to write while chasing a slow build — satisfies every assertion above.
     */
    private static String rootBuild() throws IOException {
        return JavaSource.withoutComments(RootBuild.read("build.gradle.kts"));
    }
}
