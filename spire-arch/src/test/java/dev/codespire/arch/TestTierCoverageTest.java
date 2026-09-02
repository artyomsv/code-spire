package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every module that has tests belongs to exactly one CI tier.
 *
 * <p>CI runs three Gradle lifecycle tasks: {@code testFast} for the modules whose tests need no
 * Docker, {@code testServices} for the three deployables, whose {@code @QuarkusTest}s boot Postgres
 * and Kafka Dev Services containers and are the slow half of the suite, and {@code testE2e} for
 * modules whose tests drive a stack they do not own.
 *
 * <p>The third tier is not "even slower". What separates it is ownership: a service test BOOTS what it
 * talks to, so it is hermetic and can run on the PR path; an e2e test is handed a running stack and a
 * containerised GitLab, so it can only run where one exists. That is why it is nightly, and why a
 * module landing in the wrong tier fails differently in each direction — a fast-tier module that
 * needs a stack reddens every PR, and an e2e-tier module that does not need one is silently never run
 * on the PR path.
 *
 * <p>A module in neither tier is still compiled by the build and is <em>tested by nothing</em>, while
 * CI reports green. That is the same vacuity as a snapshot gate that iterates an empty list and reads
 * zero types as zero failures — the hole closed in {@code ContractSchemaSnapshotTest} on 2026-08-02.
 * The split is therefore declared in the root build, where this test can see it, rather than as a
 * list of module paths inside a workflow file where nothing checks it.
 *
 * <p>Reads the declarations as source text, like every other check in this module. The alternative —
 * querying Gradle's project model — would need a Gradle-aware test runtime to say something a regex
 * over two files already says.
 */
class TestTierCoverageTest {

    private static final String FAST_TIER = "fastTestModules";

    private static final String SERVICE_TIER = "serviceTestModules";

    private static final String E2E_TIER = "e2eTestModules";

    private static final List<String> ALL_TIERS = List.of(FAST_TIER, SERVICE_TIER, E2E_TIER);

    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\(\"([^\"]+)\"\\)", Pattern.MULTILINE);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    void everyModuleWithTestsIsInExactlyOneTier() throws IOException {
        List<String> unassigned = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String module : includedModules()) {
            if (!hasTests(module)) {
                continue;
            }
            int tiers = 0;
            for (String tier : ALL_TIERS) {
                if (tierList(tier).contains(module)) {
                    tiers++;
                }
            }
            if (tiers > 1) {
                duplicated.add(module);
            } else if (tiers == 0) {
                unassigned.add(module);
            }
        }

        assertTrue(unassigned.isEmpty(),
                "These modules have tests but belong to no CI tier, so CI never runs them: " + unassigned
                        + ". Add each to one of " + ALL_TIERS + " in the root build.gradle.kts.");
        assertTrue(duplicated.isEmpty(),
                "These modules are in more than one CI tier, so their tests run twice per build: "
                        + duplicated);
    }

    @Test
    void noTierNamesAModuleThatIsNotIncluded() throws IOException {
        Set<String> included = includedModules();
        for (String tier : ALL_TIERS) {
            for (String module : tierList(tier)) {
                assertTrue(included.contains(module),
                        tier + " names '" + module + "', which settings.gradle.kts does not include. A stale "
                                + "entry makes the tier task fail to resolve, or silently drops a renamed module.");
            }
        }
    }

    /**
     * Guards the guard. A parser that matched nothing would satisfy every assertion above: an empty
     * tier list contains no unassigned modules and names no missing ones.
     */
    @Test
    void theDeclarationsWereActuallyFound() throws IOException {
        assertFalse(tierList(FAST_TIER).isEmpty(), FAST_TIER + " parsed to nothing");
        assertFalse(tierList(E2E_TIER).isEmpty(), E2E_TIER + " parsed to nothing");

        // Asserted by MEMBERSHIP rather than by size. An exact count of three was a change-detector:
        // it froze a number that legitimately grows, so a module correctly joining this tier failed
        // a test about parsing. What actually has to hold is that the three deployables are here —
        // which also proves the regex read a whole list rather than a truncated one, since a partial
        // match would drop one of them.
        assertTrue(tierList(SERVICE_TIER).containsAll(
                        List.of("spire-gateway", "spire-orchestrator", "spire-review-worker")),
                SERVICE_TIER + " must contain the three deployables, was " + tierList(SERVICE_TIER));
        assertTrue(includedModules().size() > 10,
                "settings.gradle.kts parsed to " + includedModules().size() + " modules, which is too few");
    }

    private static Set<String> tierList(String name) throws IOException {
        return RootBuild.declaredList(name);
    }

    private static Set<String> includedModules() throws IOException {
        Matcher matcher = INCLUDE.matcher(Files.readString(repoRoot().resolve("settings.gradle.kts")));
        Set<String> modules = new LinkedHashSet<>();
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        return modules;
    }

    private static boolean hasTests(String module) throws IOException {
        Path tests = repoRoot().resolve(module).resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(tests)) {
            return walk.anyMatch(path -> path.toString().endsWith(".java"));
        }
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }
}
