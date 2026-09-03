package dev.codespire.arch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The root build's declarations, read as text, for the checks in this module that hold them to
 * account.
 *
 * <p>Every guard here reads source rather than querying Gradle's project model, because the leaks
 * that caused real defects were string literals and a model query needs a Gradle-aware test runtime
 * to say what a regex over two files already says.
 *
 * <p><b>{@link #declaredList} asserts that it matched.</b> A parser that silently returns an empty
 * set satisfies almost any assertion built on it: an empty list contains no forbidden module and
 * names no missing one. That is the vacuity hole {@code ContractSchemaSnapshotTest} shipped with,
 * and it is why failing to find the declaration is an error here rather than an empty answer.
 */
final class RootBuild {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private RootBuild() {
    }

    /**
     * A {@code val <name> = listOf("a", "b")} declaration from the root build.
     *
     * @throws AssertionError if the declaration is not found, rather than returning an empty set
     */
    static Set<String> declaredList(String name) throws IOException {
        Matcher declaration = Pattern
                .compile("val\\s+" + name + "\\s*=\\s*listOf\\(([^)]*)\\)")
                .matcher(read("build.gradle.kts"));
        assertTrue(declaration.find(),
                "no `val " + name + " = listOf(...)` in the root build.gradle.kts");
        Set<String> entries = new LinkedHashSet<>();
        Matcher entry = QUOTED.matcher(declaration.group(1));
        while (entry.find()) {
            entries.add(entry.group(1));
        }
        return entries;
    }

    /** A file at the repository root, verbatim. */
    static String read(String fileName) throws IOException {
        return Files.readString(repoRoot().resolve(fileName));
    }

    /**
     * The repository root, handed in by the Gradle task rather than guessed from the working
     * directory, so a scan behaves the same from an IDE and from the build.
     */
    static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }
}
