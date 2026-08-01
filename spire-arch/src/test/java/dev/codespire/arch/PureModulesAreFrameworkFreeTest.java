package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The pure modules import the JDK and each other, and nothing else.
 *
 * <p>{@code spire-contract} is the domain: events, commands, value types, ports and the
 * {@code ReviewLifecycle} decider, all pure functions unit-tested without a runtime.
 * {@code spire-diff} is the parsing library. Both declare framework-freedom in their own build
 * files, and it is what lets them be Apache-2.0 libraries a plugin author can depend on without
 * inheriting a runtime (LICENSING.md, ADR-021).
 *
 * <p>Until now that was a convention in CLAUDE.md and a comment in two build files — nothing
 * checked it. A single {@code import io.quarkus...} added to a domain record during a hurried fix
 * would compile, pass every test, and only be noticed by a reviewer who happened to look at the
 * import block.
 *
 * <p><b>The one exception is deliberate and is the reason this check exists in this shape.</b> The
 * sealed wire hierarchies carry Jackson's polymorphic type annotations; see {@link #ALLOWED}.
 * Extracting them was considered and rejected — the alternative is a mix-in registry that every
 * ObjectMapper in every service must remember to install, where a missed site is a runtime wire
 * break rather than a compile error. Documenting and bounding the exception is the better trade,
 * and this test is what bounds it: jackson-annotations is permitted, and nothing else is.
 */
class PureModulesAreFrameworkFreeTest {

    /** Modules whose build files declare them framework-free. */
    private static final List<String> PURE_MODULES = List.of("spire-contract", "spire-diff");

    /** What a pure module may always import: the JDK, and the pure modules themselves. */
    private static final List<String> DOMAIN_PREFIXES =
            List.of("java.", "dev.codespire.contract.", "dev.codespire.diff.");

    /**
     * Third-party imports permitted anyway, each with the reason. An entry here is a deliberate,
     * reviewable act — adding one should feel like amending the rule, because it is.
     */
    private static final Map<String, String> ALLOWED = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("com.fasterxml.jackson.annotation.",
                "Annotations only (jackson-annotations, no databind): the sealed IntegrationEvent and "
                        + "ActionCommand hierarchies ARE the Kafka wire contract, so their type "
                        + "discriminators live with them. Moving them to per-service mix-ins would spread "
                        + "one registry across every ObjectMapper in three services, where a missed site "
                        + "fails at runtime instead of at compile time.");
        return Collections.unmodifiableMap(allowed);
    }

    @Test
    void pureModulesImportOnlyTheJdkThemselvesAndAllowedExceptions() {
        List<String> violations = new ArrayList<>();
        for (Path source : pureSources()) {
            violations.addAll(foreignImportsIn(source));
        }
        if (!violations.isEmpty()) {
            fail(report(violations));
        }
    }

    /**
     * An exception nobody uses is an open door left ajar. If the Jackson annotations ever do leave
     * the contract, this fails so the entry goes with them rather than quietly permitting a
     * re-introduction later.
     */
    @Test
    void everyAllowedExceptionIsStillUsed() {
        List<String> unused = new ArrayList<>(ALLOWED.keySet());
        for (Path source : pureSources()) {
            unused.removeIf(prefix -> importsIn(source).stream().anyMatch(i -> i.startsWith(prefix)));
        }
        if (!unused.isEmpty()) {
            fail("Unused ALLOWED entries in " + PureModulesAreFrameworkFreeTest.class.getSimpleName()
                    + ":\n\n  " + String.join("\n  ", unused)
                    + "\n\nNothing imports these any more — drop the entry so the modules are fully "
                    + "protected again.\n");
        }
    }

    /**
     * Guards the guard. A renamed module or a wrong repo root would make the check above pass by
     * scanning nothing — the one failure mode of a check like this that nobody would notice.
     */
    @Test
    void theScanReachesEveryPureModulesSources() {
        List<Path> sources = pureSources();
        assertTrue(sources.size() > 40,
                "expected the pure modules' sources (~60 files), scanned only " + sources.size());
        for (String module : PURE_MODULES) {
            long scanned = sources.stream().filter(p -> relative(p).startsWith(module + "/")).count();
            assertTrue(scanned >= 5, "only " + scanned + " sources scanned for " + module);
        }
    }

    /** Every imported type in a source file, comments stripped so a commented-out import is not one. */
    private static List<String> importsIn(Path source) {
        List<String> imports = new ArrayList<>();
        for (String line : JavaSource.withoutComments(read(source)).split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring("import ".length()).replaceFirst("^static ", "")
                        .replace(";", "").strip());
            }
        }
        return imports;
    }

    /** Imports that are neither the JDK, nor a pure module, nor an allowed exception. */
    private static List<String> foreignImportsIn(Path source) {
        List<String> found = new ArrayList<>();
        for (String imported : importsIn(source)) {
            boolean permitted = DOMAIN_PREFIXES.stream().anyMatch(imported::startsWith)
                    || ALLOWED.keySet().stream().anyMatch(imported::startsWith);
            if (!permitted) {
                found.add(relative(source) + "\n      import " + imported + ";");
            }
        }
        return found;
    }

    private static String report(List<String> violations) {
        StringBuilder message = new StringBuilder();
        message.append(violations.size()).append(" framework import(s) in a pure module:\n\n");
        violations.forEach(violation -> message.append("  ").append(violation).append("\n"));
        message.append("""

                spire-contract and spire-diff must import only the JDK and each other. They are the
                Apache-2.0 libraries a plugin author depends on (LICENSING.md), and the domain code
                whose deciders and views are unit-tested as pure functions with no runtime. A
                framework import here forces that runtime on every consumer.

                Resolve one of these ways, in order of preference:

                  1. Keep the framework concern in the service that has the framework. A port on the
                     domain side, an adapter on the service side, is the shape the whole codebase
                     already uses.
                  2. Express what you need as a plain Java type the domain owns, and let the service
                     map it. ScmCredential and PromptTemplate are worked examples.
                  3. Only if neither fits: add the import prefix to ALLOWED with the reason, making
                     the exception explicit and reviewable. There is currently exactly one, and its
                     entry explains why extraction would be worse.
                """);
        return message.toString();
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }

    private static List<Path> pureSources() {
        List<Path> sources = new ArrayList<>();
        for (String module : PURE_MODULES) {
            Path main = repoRoot().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                throw new IllegalStateException("Not a source directory: " + main
                        + " — has the module been renamed? Update PURE_MODULES.");
            }
            try (Stream<Path> tree = Files.walk(main)) {
                tree.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot walk " + main, e);
            }
        }
        return sources;
    }

    private static String relative(Path source) {
        return repoRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }
}
