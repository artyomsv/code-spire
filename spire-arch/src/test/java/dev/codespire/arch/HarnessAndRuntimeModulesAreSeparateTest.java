package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness family and the runtime family never meet, except in the run worker that composes them.
 *
 * <p>A harness adapter says what runs inside a run unit; a runtime arm says where the unit is placed.
 * If a runtime arm learns which harness it carries, every new harness needs a change in every arm, and
 * the Kubernetes arm would grow a branch per vendor. If a harness learns its runtime, the same holds the
 * other way round. The only place that may name both is the composition root, the run worker.
 *
 * <p>Reads build files and sources as text, like the other checks here, so a string or an import is seen
 * as well as a project dependency. A family is a set of module names: harness modules are
 * {@code spire-harness} and {@code spire-harness-*}; runtime arms are {@code spire-runtime-*}. The
 * {@code spire-runtime} SPI is not an arm: every arm and the run worker depend on it by design.
 */
class HarnessAndRuntimeModulesAreSeparateTest {

    private static final String HARNESS_SPI = ":spire-harness";

    private static final String HARNESS_PREFIX = ":spire-harness";

    private static final String RUNTIME_ANY_PREFIX = ":spire-runtime";

    private static final String RUNTIME_ARM_PREFIX = ":spire-runtime-";

    private static final String HARNESS_IMPORT = "import dev.codespire.harness.";

    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("project\\(\"(:[^\"]+)\"\\)");

    @Test
    void noHarnessModuleDependsOnAnotherHarnessOrOnARuntime() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String module : harnessModules()) {
            problems.addAll(harnessProblems(module, buildText(module)));
        }
        assertEquals(List.of(), problems, "A harness module may depend on the harness SPI and nothing else "
                + "of either family. Move the shared part into spire-harness.");
    }

    @Test
    void noRuntimeArmDependsOnOrImportsAHarness() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String module : runtimeModules()) {
            problems.addAll(runtimeProblems(module, buildText(module), mainImports(module)));
        }
        assertEquals(List.of(), problems, "A runtime arm places a unit and must not know what runs in it. "
                + "State the need as a neutral capability on the spire-runtime SPI.");
    }

    @Test
    void onlyTheRunWorkerDependsOnBothFamilies() throws IOException {
        List<String> both = new ArrayList<>();
        for (String module : allModules()) {
            List<String> dependencies = projectDependencies(buildText(module));
            boolean usesHarness = dependencies.stream().anyMatch(d -> d.startsWith(HARNESS_PREFIX));
            boolean usesRuntimeArm = dependencies.stream().anyMatch(d -> d.startsWith(RUNTIME_ARM_PREFIX));
            if (usesHarness && usesRuntimeArm) {
                both.add(module);
            }
        }
        assertEquals(List.of("spire-run-worker"), both,
                "Only the run worker composes a harness with a runtime arm.");
    }

    /** Guards the guard: a wrong root or a renamed module would make every check above scan nothing. */
    @Test
    void theScanReachesBothFamilies() throws IOException {
        assertTrue(harnessModules().contains("spire-harness-codex"), "harness modules: " + harnessModules());
        assertTrue(runtimeModules().containsAll(List.of("spire-runtime", "spire-runtime-docker")),
                "runtime modules: " + runtimeModules());
        assertFalse(mainImports("spire-runtime-docker").isEmpty(),
                "no imports read from spire-runtime-docker: has the source layout changed?");
    }

    @Test
    void theRulesBiteOnAForbiddenDependency() {
        String harnessInRuntime = "dependencies {\n    implementation(project(\":spire-harness-codex\"))\n}\n";
        assertEquals(1, runtimeProblems("spire-runtime-k8s", harnessInRuntime, List.of()).size());

        String importInRuntime = "import dev.codespire.harness.HarnessAdapter;";
        assertEquals(1, runtimeProblems("spire-runtime-k8s", "", List.of(importInRuntime)).size());

        String harnessInHarness = "implementation(project(\":spire-harness-claude-code\"))";
        assertEquals(1, harnessProblems("spire-harness-codex", harnessInHarness).size());

        String runtimeInHarness = "implementation(project(\":spire-runtime-docker\"))";
        assertEquals(1, harnessProblems("spire-harness-codex", runtimeInHarness).size());

        String allowed = "implementation(project(\":spire-harness\"))\n"
                + "testImplementation(testFixtures(project(\":spire-harness\")))";
        assertEquals(0, harnessProblems("spire-harness-codex", allowed).size());
        assertEquals(0, runtimeProblems("spire-runtime-k8s",
                "implementation(project(\":spire-runtime\"))", List.of()).size());
    }

    static List<String> harnessProblems(String module, String buildText) {
        List<String> problems = new ArrayList<>();
        for (String dependency : projectDependencies(buildText)) {
            boolean otherHarness = dependency.startsWith(HARNESS_PREFIX) && !dependency.equals(HARNESS_SPI);
            if (otherHarness || dependency.startsWith(RUNTIME_ANY_PREFIX)) {
                problems.add(module + " depends on " + dependency);
            }
        }
        return problems;
    }

    static List<String> runtimeProblems(String module, String buildText, List<String> sourceImports) {
        List<String> problems = new ArrayList<>();
        for (String dependency : projectDependencies(buildText)) {
            if (dependency.startsWith(HARNESS_PREFIX)) {
                problems.add(module + " depends on " + dependency);
            }
        }
        for (String line : sourceImports) {
            if (line.strip().startsWith(HARNESS_IMPORT)) {
                problems.add(module + " imports " + line.strip());
            }
        }
        return problems;
    }

    private static List<String> projectDependencies(String buildText) {
        List<String> dependencies = new ArrayList<>();
        Matcher matcher = PROJECT_DEPENDENCY.matcher(buildText);
        while (matcher.find()) {
            dependencies.add(matcher.group(1));
        }
        return dependencies;
    }

    private static List<String> harnessModules() throws IOException {
        return allModules().stream().filter(m -> m.equals("spire-harness") || m.startsWith("spire-harness-")).toList();
    }

    private static List<String> runtimeModules() throws IOException {
        return allModules().stream().filter(m -> m.equals("spire-runtime") || m.startsWith("spire-runtime-")).toList();
    }

    private static List<String> allModules() throws IOException {
        try (Stream<Path> entries = Files.list(RootBuild.repoRoot())) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("spire-"))
                    .filter(path -> Files.isRegularFile(path.resolve("build.gradle.kts")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static String buildText(String module) throws IOException {
        return Files.readString(RootBuild.repoRoot().resolve(module).resolve("build.gradle.kts"));
    }

    private static List<String> mainImports(String module) {
        Path main = RootBuild.repoRoot().resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            return List.of();
        }
        List<String> imports = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(main)) {
            for (Path source : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                Files.readAllLines(source).stream()
                        .filter(line -> line.strip().startsWith("import "))
                        .forEach(imports::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sources of " + module, e);
        }
        return imports;
    }
}
