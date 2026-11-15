package dev.codespire.arch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text readers shared by the checks on docker.yml's and release.yml's build matrices and the
 * Dockerfiles they build: the matrix parser and the scan for Dockerfiles that upgrade their base OS
 * packages.
 */
final class DockerBuildSources {

    private DockerBuildSources() {
    }

    /**
     * An OS-package upgrade in any of the spellings this repository could plausibly use. Matched
     * against a logical instruction with continuations already joined, so a multi-line {@code RUN}
     * is one string — {@code spire-publisher/Dockerfile} chains its upgrade into an {@code adduser}.
     */
    static final Pattern UPGRADES_OS_PACKAGES =
            Pattern.compile("\\bapk\\b[^\\r\\n]*\\bupgrade\\b|\\bapt-get\\b[^\\r\\n]*\\bupgrade\\b");

    /**
     * One build matrix's {@code include:} list — every following line indented past the four spaces
     * a sibling key like {@code steps:} sits at. docker.yml has a matrix in each of two jobs
     * ({@code images} and {@code agents}) and every one is read: an image published from a second
     * job must not escape the check because the parser stopped at the first.
     *
     * <p>Scoping to this block is not tidiness. A workflow step is also spelled {@code - name: …},
     * so a pattern that only looked for that read {@code - name: Build} as a matrix entry and the
     * check failed against a correct workflow.
     *
     * <p><b>Line breaks are {@code \R}, not {@code \n}, and that is the whole reason this check ran
     * green in CI while failing on every developer machine.</b> {@code core.autocrlf} is on for
     * Windows checkouts, so the workflow is CRLF on disk; Java's {@code .} excludes {@code \r}, so
     * {@code .*\n} could never reach the newline and the {@code include:} block "was not found".
     * The failure then read as "the parser and the workflow disagree about its shape" — a message
     * about the workflow, for a fault in the parser. The Dockerfile splitter below already used
     * {@code \r?\n}; these two did not, which is the same fix-on-one-of-two-siblings shape this
     * repository keeps paying for.
     */
    static final Pattern MATRIX_INCLUDE =
            Pattern.compile("^ +include:\\R(?<entries>(?:^ {5,}.*\\R)+)", Pattern.MULTILINE);

    /** One `- name: &lt;image&gt;` block within that list, up to the next entry. */
    static final Pattern MATRIX_ENTRY = Pattern.compile(
            "^ +- name: (?<name>\\S+)\\R(?<body>(?:^ +\\w+: .*\\R)+)", Pattern.MULTILINE);

    static final Pattern DECLARED_DOCKERFILE =
            Pattern.compile("^ +dockerfile: (.+)$", Pattern.MULTILINE);

    /**
     * The body of one matrix entry, by image name.
     *
     * @throws AssertionError if no such entry is found, rather than returning an empty body — an
     *         empty body contains no {@code apkUpgrade: true} and would read as a deliberate absence
     */
    static String entryBody(String workflow, String imageName) {
        for (String include : matrixIncludes(workflow)) {
            Matcher entry = MATRIX_ENTRY.matcher(include);
            while (entry.find()) {
                if (entry.group("name").equals(imageName)) {
                    return entry.group("body");
                }
            }
        }
        throw new AssertionError("no matrix entry named " + imageName + " — the parser and the "
                + "workflow disagree about the matrix's shape");
    }

    /**
     * Every matrix's {@code include:} list, in file order.
     *
     * @throws AssertionError if no block is found, rather than returning an empty list — no entries
     *         parsed means every per-image assertion passes over nothing
     */
    static List<String> matrixIncludes(String workflow) {
        Matcher matrix = MATRIX_INCLUDE.matcher(workflow);
        List<String> includes = new ArrayList<>();
        while (matrix.find()) {
            includes.add(matrix.group("entries"));
        }
        assertFalse(includes.isEmpty(), "no `include:` block found in the workflow's build matrix — "
                + "the parser and the workflow disagree about its shape");
        return includes;
    }

    /** The workflow that builds every image per merge and publishes {@code :edge}. */
    static final String DOCKER_WORKFLOW = ".github/workflows/docker.yml";

    /** The workflow that publishes every image at a version on a {@code v*} tag. */
    static final String RELEASE_WORKFLOW = ".github/workflows/release.yml";

    /**
     * Both workflows that build published images. Each image's build args are spelled once per
     * workflow, so a check on one alone let the other drift: release.yml sent no
     * {@code APK_UPGRADE_BUST} at all until 2026-11-15 while docker.yml's matrix passed every check.
     */
    static final List<String> PUBLISHING_WORKFLOWS = List.of(DOCKER_WORKFLOW, RELEASE_WORKFLOW);

    /** Image name to the absolute path of the Dockerfile the given workflow builds it from. */
    static Map<String, Path> builtImages(String workflowPath) throws IOException {
        Map<String, Path> images = new LinkedHashMap<>();
        for (String include : matrixIncludes(RootBuild.read(workflowPath))) {
            Matcher entry = MATRIX_ENTRY.matcher(include);
            while (entry.find()) {
                Matcher file = DECLARED_DOCKERFILE.matcher(entry.group("body"));
                assertTrue(file.find(), "matrix entry " + entry.group("name") + " names no dockerfile");
                String declared = file.group(1).trim().replaceFirst("^\\./", "");
                images.put(entry.group("name"), RootBuild.repoRoot().resolve(declared).normalize());
            }
        }
        return images;
    }

    /**
     * Every Dockerfile in the tree that upgrades its base OS packages, mapped to the logical
     * instruction that does it — continuations joined, so a chained multi-line RUN reads as one.
     */
    static Map<Path, String> dockerfilesThatUpgradeOsPackages() throws IOException {
        Map<Path, String> found = new LinkedHashMap<>();
        for (Path file : dockerfiles()) {
            for (String instruction : logicalInstructions(Files.readString(file))) {
                if (UPGRADES_OS_PACKAGES.matcher(instruction).find()) {
                    found.merge(file, instruction, (a, b) -> a + "\n" + b);
                }
            }
        }
        return found;
    }

    /** Every `Dockerfile*` in the tree, skipping build output and vendored trees. */
    private static Set<Path> dockerfiles() throws IOException {
        try (Stream<Path> tree = Files.walk(RootBuild.repoRoot())) {
            Set<Path> files = new LinkedHashSet<>();
            tree.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("Dockerfile"))
                    .filter(DockerBuildSources::isSourceFile)
                    .sorted()
                    .forEach(files::add);
            return files;
        }
    }

    private static boolean isSourceFile(Path file) {
        String path = RootBuild.repoRoot().relativize(file).toString().replace('\\', '/');
        return !path.contains("/build/")
                && !path.contains("node_modules/")
                && !path.startsWith(".git/");
    }

    /**
     * A Dockerfile's instructions with backslash continuations joined and comment lines dropped, so
     * a multi-line RUN is one string and a commented-out upgrade is not mistaken for a real one.
     */
    private static List<String> logicalInstructions(String dockerfile) {
        List<String> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : dockerfile.split("\r?\n", -1)) {
            if (current.isEmpty() && line.stripLeading().startsWith("#")) {
                continue;
            }
            String body = line.stripTrailing();
            boolean continues = body.endsWith("\\");
            current.append(continues ? body.substring(0, body.length() - 1) : body).append(' ');
            if (!continues) {
                instructions.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            instructions.add(current.toString().trim());
        }
        return instructions;
    }
}
