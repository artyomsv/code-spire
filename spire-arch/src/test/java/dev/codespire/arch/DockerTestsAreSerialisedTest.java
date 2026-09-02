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
 * Two test tasks drive the one real Docker daemon, and the build runs modules in parallel.
 *
 * <p>{@code org.gradle.parallel=true} plus a {@code testServices} that depends on five modules means
 * {@code spire-runtime-docker} and {@code spire-run-worker} create, inspect and destroy containers
 * against the same daemon at the same time. Observed on the M1 branch's predecessor:
 * {@code DockerRunRuntimeIT} failed two cases under {@code testServices} and passed 15 of 15 run
 * alone, twice.
 *
 * <p><b>The expensive part is not the flake, it is what the flake looks like.</b> The two failures
 * were "no such container" on an inspect and "destroying it must actually remove it" — the exact
 * symptoms the runtime is written to prevent, so the natural reading is that container lifecycle
 * broke. A red that impersonates a real defect costs more than a red that announces itself.
 *
 * <p>So the daemon is a Gradle shared build service with {@code maxParallelUsages = 1}: the two
 * tasks queue behind each other while the other three service modules still run in parallel. That
 * second half is the reason this is a service rather than switching {@code org.gradle.parallel} off,
 * and it is asserted below — the cheap fix passes a "one at a time" check and slows every build.
 *
 * <p>Which modules need the lock is <b>derived, not trusted</b>. Reading the declared list and
 * checking it against itself would pass forever; instead the test scans test sources for daemon use
 * and requires the declaration to match. A module that starts driving Docker and forgets to declare
 * itself fails here rather than months later as an unexplained flake.
 *
 * <p>That paid for itself immediately: the plan for this change named two modules, and the scan
 * found three. {@code spire-e2e} shells out to {@code docker compose exec} for its psql and Rails
 * probes. It sits in the nightly tier, so it never meets {@code testServices} in CI and the lock
 * costs it nothing — but the lock is about the daemon, not about a tier, and a local run of both
 * tiers is exactly the case a tier-shaped rule would miss.
 */
class DockerTestsAreSerialisedTest {

    /** The Gradle shared service that serialises them, and the property that makes it serialise. */
    private static final String LOCK_SERVICE = "dockerDaemonLock";

    private static final Pattern MAX_PARALLEL =
            Pattern.compile("maxParallelUsages\\.set\\((\\d+)\\)|maxParallelUsages\\s*=\\s*(\\d+)");

    /** The declared list this test holds to account. */
    private static final Pattern DECLARED_LIST = Pattern.compile(
            "val\\s+dockerDrivingModules\\s*=\\s*listOf\\(([^)]*)\\)", Pattern.DOTALL);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * Daemon use, in the forms this repository actually writes it: the client library, the real
     * runtime arm constructed in a test, and the CLI shelled out to. Matched against comment-stripped
     * source, because a test that merely mentions {@code docker inspect} in a comment explaining a
     * security property is not a test that talks to the daemon.
     */
    private static final List<Pattern> DAEMON_USE = List.of(
            Pattern.compile("com\\.github\\.dockerjava"),
            Pattern.compile("new\\s+DockerRunRuntime\\s*\\("),
            // The CLI name as a bare literal, rather than a ProcessBuilder shape. Both callers build
            // their argv a line at a time — `argv.add("docker")` — so a pattern anchored on the
            // constructor missed spire-run-worker entirely and the scan quietly under-reported.
            Pattern.compile("\"docker\""));

    /**
     * The scanner's own module. Every pattern above appears in this file as a literal, so including
     * it would classify the module that owns the check as a daemon driver. spire-arch reads text and
     * boots nothing, which is why the exclusion is safe rather than convenient.
     */
    private static final String SCANNER_MODULE = "spire-arch";

    @Test
    void everyModuleWhoseTestsDriveTheDaemonIsDeclared() throws IOException {
        Set<String> observed = modulesWhoseTestsDriveTheDaemon();
        Set<String> declared = declaredDockerDrivingModules();

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

        assertTrue(build.contains(LOCK_SERVICE),
                "the root build declares no " + LOCK_SERVICE + " shared service");
        Matcher limit = MAX_PARALLEL.matcher(build);
        assertTrue(limit.find(), LOCK_SERVICE + " sets no maxParallelUsages, so it serialises nothing");
        String value = limit.group(1) != null ? limit.group(1) : limit.group(2);
        assertEquals("1", value,
                "maxParallelUsages must be 1; anything higher lets the two tasks meet on the daemon again");
        assertTrue(build.contains("usesService(" + LOCK_SERVICE + ")"),
                "the lock is declared but no test task uses it");
    }

    @Test
    void theOtherServiceModulesAreNotSerialised() throws IOException {
        Set<String> declared = declaredDockerDrivingModules();

        // The whole reason for a shared service rather than org.gradle.parallel=false. Dropping
        // parallelism would satisfy the test above and slow every build in the repository, so the
        // negative is asserted directly: these three must stay free to run alongside everything else.
        for (String parallelStill : List.of("spire-gateway", "spire-orchestrator", "spire-review-worker")) {
            assertFalse(declared.contains(parallelStill),
                    parallelStill + " boots Dev Services containers but drives no daemon of its own; "
                            + "serialising it would trade a flake for a slower build on every task");
        }
    }

    private static Set<String> modulesWhoseTestsDriveTheDaemon() throws IOException {
        Set<String> driving = new TreeSet<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                String name = module.getFileName().toString();
                if (!name.startsWith("spire-") || name.equals(SCANNER_MODULE)) {
                    continue;
                }
                Path tests = module.resolve("src/test");
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

    private static Set<String> declaredDockerDrivingModules() throws IOException {
        Matcher declared = DECLARED_LIST.matcher(rootBuild());
        if (!declared.find()) {
            return Set.of();
        }
        Set<String> modules = new TreeSet<>();
        Matcher entry = QUOTED.matcher(declared.group(1));
        while (entry.find()) {
            modules.add(entry.group(1));
        }
        return modules;
    }

    private static String rootBuild() throws IOException {
        return Files.readString(repoRoot().resolve("build.gradle.kts"));
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it");
        }
        return Path.of(root);
    }
}
