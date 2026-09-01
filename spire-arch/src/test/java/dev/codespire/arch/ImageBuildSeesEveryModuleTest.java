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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every module {@code settings.gradle.kts} includes must also be COPYed into the production image's
 * dependency layer.
 *
 * <p>The {@code Dockerfile} copies build files before source so the dependency layer caches
 * independently of source edits, and it names each module by hand. Gradle refuses to configure an
 * included project whose directory is absent, so a module added to settings but missing from that
 * list makes {@code :spire-<service>:dependencies} fail — before one service class is compiled, and
 * for a module the services do not even depend on.
 *
 * <p>Found the expensive way: adding {@code spire-e2e} broke every production image build with
 * "Configuring project ':spire-e2e' without an existing directory is not allowed", while
 * {@code ./gradlew build} stayed green. Nothing on the PR path builds these images, so the first
 * signal was a developer running the packaged stack.
 *
 * <p>Reads both files as source text, like every other check in this module.
 */
class ImageBuildSeesEveryModuleTest {

    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*include\\(\"([^\"]+)\"\\)", Pattern.MULTILINE);

    /** Matches `COPY <module>/build.gradle.kts <module>/`, capturing the module once. */
    private static final Pattern COPY_BUILD_FILE =
            Pattern.compile("^\\s*COPY\\s+([A-Za-z0-9._-]+)/build\\.gradle\\.kts\\s", Pattern.MULTILINE);

    @Test
    void everyIncludedModuleIsCopiedIntoTheDependencyLayer() throws IOException {
        Set<String> copied = copiedModules();

        List<String> missing = new ArrayList<>();
        for (String module : includedModules()) {
            if (!copied.contains(module)) {
                missing.add(module);
            }
        }

        assertTrue(missing.isEmpty(),
                "settings.gradle.kts includes these modules but the Dockerfile never copies their build "
                        + "files: " + missing + ". Gradle refuses to configure an included project whose "
                        + "directory is absent, so every production image build fails at "
                        + "`:spire-<service>:dependencies`. Add `COPY <module>/build.gradle.kts <module>/` "
                        + "to the Dockerfile, keeping the list alphabetical.");
    }

    /**
     * The other direction. A stale COPY names a directory the build context no longer has, which
     * fails the image build just as hard — and reads, in the log, like the module was deleted wrongly
     * rather than like the Dockerfile was left behind.
     */
    @Test
    void theDockerfileCopiesNoModuleThatSettingsDoesNotInclude() throws IOException {
        Set<String> included = includedModules();

        List<String> stale = new ArrayList<>();
        for (String module : copiedModules()) {
            if (!included.contains(module)) {
                stale.add(module);
            }
        }

        assertTrue(stale.isEmpty(),
                "The Dockerfile copies build files for modules settings.gradle.kts does not include: "
                        + stale + ". The COPY fails because the path is not in the build context.");
    }

    /**
     * Guards the guard. Two regexes that matched nothing would satisfy both assertions above — no
     * included module is missing from an empty set it is never compared against, and an empty COPY
     * set names nothing stale. This is the same vacuity hole closed in ContractSchemaSnapshotTest.
     *
     * <p>Asserts only that each parser FOUND something. Comparing the two sizes here was tried and
     * removed: it duplicates the drift check above, so one real drift reddened two tests and the
     * report no longer said which problem it had — a vacuity guard that fails for non-vacuous reasons
     * stops being a signal about the parser.
     */
    @Test
    void bothFilesWereActuallyParsed() throws IOException {
        assertTrue(includedModules().size() > 10,
                "settings.gradle.kts parsed to " + includedModules().size() + " modules, which is too few");
        assertFalse(copiedModules().isEmpty(), "the Dockerfile's COPY lines parsed to nothing");
    }

    private static Set<String> includedModules() throws IOException {
        return matches(INCLUDE, Files.readString(repoRoot().resolve("settings.gradle.kts")));
    }

    private static Set<String> copiedModules() throws IOException {
        return matches(COPY_BUILD_FILE, Files.readString(repoRoot().resolve("Dockerfile")));
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
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
