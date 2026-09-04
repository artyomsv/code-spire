package dev.codespire.arch;

import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image that upgrades its base OS packages must actually run that upgrade on every build.
 *
 * <p>{@code RUN apk --no-cache upgrade} has no input that changes. With {@code cache-from} restoring
 * a layer cache, BuildKit re-used it on every build, so the upgrade ran exactly once — and its only
 * cache key was the base image, which means the mitigation refreshed precisely when the base
 * retagged. That is the wait it exists to skip, so the whole thing was inert while three files said
 * it worked.
 *
 * <p><b>Measured, not inferred.</b> Docker workflow run 33810550375 logged
 * {@code #48 [stage-1 2/10] RUN apk --no-cache upgrade} followed by {@code #48 CACHED} for all three
 * services, while 102 Trivy alerts stood open against openssl 3.5.7-r0 and libexpat 2.8.3-r0 — both
 * of which the live Alpine index had already fixed (3.5.8-r0, 2.8.4-r0), and which one fresh
 * {@code apk --no-cache upgrade} on the same base installs.
 *
 * <p>Two halves, and either alone is inert, which is why one check holds both:
 *
 * <ul>
 *   <li><b>The Dockerfile must reference the argument in the upgrading command.</b> BuildKit keys a
 *       {@code RUN} on the build args it actually references, so an {@code ARG} the command never
 *       mentions changes no cache key. Declaring it and not using it looks exactly like a fix.</li>
 *   <li><b>The workflow must pass a value that differs per build.</b> {@code github.run_id} is the
 *       one already in scope that is unique per run. A constant here restores the original defect
 *       with more ceremony.</li>
 * </ul>
 *
 * <p>The reverse direction is asserted too: an image with no upgrade must NOT be sent the argument.
 * An unconsumed build arg warns on every build, and a warning nobody can act on trains people past
 * the ones they can.
 *
 * <p>Reads both files as source text, like every other check in this module. The Dockerfile list is
 * <b>derived</b> by scanning the tree rather than declared, so an image added later inherits the
 * rule instead of quietly escaping it.
 */
class ApkUpgradeIsNotCachedTest {

    /** The build argument whose value must change per build for the upgrade layer to re-run. */
    private static final String BUST = "APK_UPGRADE_BUST";

    /**
     * An OS-package upgrade in any of the spellings this repository could plausibly use. Matched
     * against a logical instruction with continuations already joined, so a multi-line {@code RUN}
     * is one string — {@code spire-publisher/Dockerfile} chains its upgrade into an {@code adduser}.
     */
    private static final Pattern UPGRADES_OS_PACKAGES =
            Pattern.compile("\\bapk\\b[^\\r\\n]*\\bupgrade\\b|\\bapt-get\\b[^\\r\\n]*\\bupgrade\\b");

    /**
     * The build matrix's {@code include:} list — every following line indented past the four spaces
     * a sibling key like {@code steps:} sits at.
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
    private static final Pattern MATRIX_INCLUDE =
            Pattern.compile("^ +include:\\R(?<entries>(?:^ {5,}.*\\R)+)", Pattern.MULTILINE);

    /** One `- name: &lt;image&gt;` block within that list, up to the next entry. */
    private static final Pattern MATRIX_ENTRY = Pattern.compile(
            "^ +- name: (?<name>\\S+)\\R(?<body>(?:^ +\\w+: .*\\R)+)", Pattern.MULTILINE);

    private static final Pattern DECLARED_DOCKERFILE =
            Pattern.compile("^ +dockerfile: (.+)$", Pattern.MULTILINE);

    @Test
    void everyImageThatUpgradesOsPackagesBustsTheLayerCache() throws IOException {
        Map<Path, String> upgrading = dockerfilesThatUpgradeOsPackages();

        assertFalse(upgrading.isEmpty(),
                "no Dockerfile in this repository was found to upgrade its base OS packages. Either "
                        + "the scan stopped reaching the tree or the mitigation was removed — both are "
                        + "failures, and an empty result must never read as a pass.");
        assertTrue(upgrading.containsKey(RootBuild.repoRoot().resolve("Dockerfile")),
                "the root Dockerfile builds all three services and is where this defect was found; "
                        + "the scan no longer sees its `apk --no-cache upgrade`, so it is reading the "
                        + "wrong tree or joining continuations wrongly. Found: " + upgrading.keySet());

        List<String> unbusted = new ArrayList<>();
        for (Map.Entry<Path, String> file : upgrading.entrySet()) {
            if (!file.getValue().contains(BUST)) {
                unbusted.add(RootBuild.repoRoot().relativize(file.getKey()).toString());
            }
        }

        assertEquals(List.of(), unbusted,
                "these Dockerfiles upgrade their base OS packages in a RUN that does not reference "
                        + BUST + ". The layer then has no input that changes, so BuildKit restores it "
                        + "from cache and the upgrade runs once — refreshing only when the base image "
                        + "retags, which is the wait it exists to skip. Add `ARG " + BUST + "=local` "
                        + "and echo the value inside the same RUN: an ARG the command never mentions "
                        + "changes no cache key.");
    }

    @Test
    void theWorkflowSendsAValueThatDiffersPerBuild() throws IOException {
        String workflow = RootBuild.read(".github/workflows/docker.yml");

        assertTrue(workflow.contains(BUST + "={0}', github.run_id"),
                "docker.yml no longer passes " + BUST + " from github.run_id. A constant value keys "
                        + "the upgrade layer the same way on every build, which is the original defect "
                        + "with an argument added. github.run_id is unique per run and already in "
                        + "scope.");
    }

    @Test
    void everyBuiltImageIsSentTheArgumentExactlyWhenItConsumesIt() throws IOException {
        Set<Path> upgrading = dockerfilesThatUpgradeOsPackages().keySet();
        Map<String, Path> built = builtImages();

        assertFalse(built.isEmpty(),
                "no `- name:` entry was parsed out of docker.yml's build matrix. An empty matrix "
                        + "satisfies every assertion below, so failing to parse is an error here "
                        + "rather than an empty answer.");

        String workflow = RootBuild.read(".github/workflows/docker.yml");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Path> image : built.entrySet()) {
            boolean consumes = upgrading.contains(image.getValue());
            boolean sent = entryBody(workflow, image.getKey()).contains("apkUpgrade: true");
            if (consumes && !sent) {
                wrong.add(image.getKey() + " builds "
                        + RootBuild.repoRoot().relativize(image.getValue())
                        + ", which consumes " + BUST + ", but its matrix entry sets no "
                        + "`apkUpgrade: true` — the build-args line then renders empty and the "
                        + "upgrade layer caches forever");
            } else if (!consumes && sent) {
                wrong.add(image.getKey() + " is sent " + BUST + " but its Dockerfile never consumes "
                        + "it — Docker warns about an unconsumed build arg on every build, and a "
                        + "warning nobody can act on trains people past the ones they can");
            }
        }

        assertEquals(List.of(), wrong,
                "docker.yml's matrix disagrees with the Dockerfiles it builds");
    }

    /**
     * The body of one matrix entry, by image name.
     *
     * @throws AssertionError if no such entry is found, rather than returning an empty body — an
     *         empty body contains no {@code apkUpgrade: true} and would read as a deliberate absence
     */
    private static String entryBody(String workflow, String imageName) {
        Matcher entry = MATRIX_ENTRY.matcher(matrixInclude(workflow));
        while (entry.find()) {
            if (entry.group("name").equals(imageName)) {
                return entry.group("body");
            }
        }
        throw new AssertionError("no matrix entry named " + imageName + " — the parser and the "
                + "workflow disagree about the matrix's shape");
    }

    /**
     * The matrix's {@code include:} list.
     *
     * @throws AssertionError if the block is not found, rather than returning an empty string — no
     *         entries parsed means every per-image assertion passes over nothing
     */
    private static String matrixInclude(String workflow) {
        Matcher matrix = MATRIX_INCLUDE.matcher(workflow);
        assertTrue(matrix.find(), "no `include:` block found in docker.yml's build matrix — the "
                + "parser and the workflow disagree about its shape");
        return matrix.group("entries");
    }

    /** Image name to the absolute path of the Dockerfile docker.yml builds it from. */
    private static Map<String, Path> builtImages() throws IOException {
        Map<String, Path> images = new LinkedHashMap<>();
        Matcher entry = MATRIX_ENTRY.matcher(
                matrixInclude(RootBuild.read(".github/workflows/docker.yml")));
        while (entry.find()) {
            Matcher file = DECLARED_DOCKERFILE.matcher(entry.group("body"));
            assertTrue(file.find(), "matrix entry " + entry.group("name") + " names no dockerfile");
            String declared = file.group(1).trim().replaceFirst("^\\./", "");
            images.put(entry.group("name"), RootBuild.repoRoot().resolve(declared).normalize());
        }
        return images;
    }

    /**
     * Every Dockerfile in the tree that upgrades its base OS packages, mapped to the logical
     * instruction that does it — continuations joined, so a chained multi-line RUN reads as one.
     */
    private static Map<Path, String> dockerfilesThatUpgradeOsPackages() throws IOException {
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
                    .filter(ApkUpgradeIsNotCachedTest::isSourceFile)
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
