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
 * <p>CI runs two Gradle lifecycle tasks: {@code testFast} for the modules whose tests need no
 * Docker, and {@code testServices} for the three deployables, whose {@code @QuarkusTest}s boot
 * Postgres and Kafka Dev Services containers and are the slow half of the suite.
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

    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\(\"([^\"]+)\"\\)", Pattern.MULTILINE);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    void everyModuleWithTestsIsInExactlyOneTier() throws IOException {
        Set<String> fast = tierList(FAST_TIER);
        Set<String> services = tierList(SERVICE_TIER);

        List<String> unassigned = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String module : includedModules()) {
            if (!hasTests(module)) {
                continue;
            }
            boolean inFast = fast.contains(module);
            boolean inServices = services.contains(module);
            if (inFast && inServices) {
                duplicated.add(module);
            } else if (!inFast && !inServices) {
                unassigned.add(module);
            }
        }

        assertTrue(unassigned.isEmpty(),
                "These modules have tests but belong to no CI tier, so CI never runs them: " + unassigned
                        + ". Add each to " + FAST_TIER + " or " + SERVICE_TIER + " in the root build.gradle.kts.");
        assertTrue(duplicated.isEmpty(),
                "These modules are in both CI tiers, so their tests run twice per build: " + duplicated);
    }

    @Test
    void neitherTierNamesAModuleThatIsNotIncluded() throws IOException {
        Set<String> included = includedModules();
        for (String tier : List.of(FAST_TIER, SERVICE_TIER)) {
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
        assertEquals(3, tierList(SERVICE_TIER).size(),
                SERVICE_TIER + " should name exactly the three deployables");
        assertTrue(includedModules().size() > 10,
                "settings.gradle.kts parsed to " + includedModules().size() + " modules, which is too few");
    }

    private static Set<String> tierList(String name) throws IOException {
        String build = Files.readString(repoRoot().resolve("build.gradle.kts"));
        Matcher declaration = Pattern
                .compile("val\\s+" + name + "\\s*=\\s*listOf\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(build);
        assertTrue(declaration.find(),
                "no `val " + name + " = listOf(...)` in the root build.gradle.kts");
        Set<String> modules = new LinkedHashSet<>();
        Matcher entry = QUOTED.matcher(declaration.group(1));
        while (entry.find()) {
            modules.add(entry.group(1));
        }
        return modules;
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
