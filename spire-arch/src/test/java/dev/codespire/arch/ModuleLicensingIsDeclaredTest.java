package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every Gradle module carries its own LICENSE, that file names the module it is in, and its text
 * is the licence LICENSING.md declares for it.
 *
 * <p>The fourth leg of the module ritual, and until now the only one nothing checked —
 * {@code TestTierCoverageTest} guards the CI tier and {@code ImageBuildSeesEveryModuleTest} guards
 * the image's dependency layer, while the licence was left to review vigilance. ADR-021 makes the
 * split per module, so a module's LICENSE is not decoration: it is the statement a third party
 * relies on when deciding whether they may build a plugin against it.
 *
 * <p><b>The failure this exists to catch is a copy-paste, and it is silent in three ways.</b> A new
 * module's LICENSE is made by copying a neighbour's, so it arrives naming the neighbour. Nothing
 * compiles it, no test reads it, and it is eighteen lines of boilerplate a reviewer's eye slides
 * over. It happened while adding {@code spire-harness}: the file was copied from
 * {@code spire-diff}, so it announced itself as spire-diff <em>and</em> inherited spire-diff's
 * closing paragraph — a statement that PR-Agent was studied as prior art, about a module whose
 * author had never looked at it. A false provenance claim in a legal file is a worse defect than
 * the wrong name above it, and both were one copy away.
 *
 * <p>The body check is the half that matters most. A name is cosmetic; the wrong licence
 * <em>text</em> means the repository states two incompatible things about the same code, and the
 * one a reader believes is the one sitting in the module.
 *
 * <p>Reads the declarations as text, like every other check here. No regex: both formats are
 * line-oriented, and a pattern that silently stopped matching after a formatting change would make
 * this pass by finding nothing.
 */
class ModuleLicensingIsDeclaredTest {

    /**
     * The grant sentence each licence opens with, by the name LICENSING.md declares.
     *
     * <p>Anchored on "Licensed under the" rather than the licence name alone, and checked in BOTH
     * directions, because a presence-only check does not discriminate here: the FSL body contains
     * the literal "Apache License, Version 2.0" in its two-year conversion clause, so asserting
     * only that an Apache module's LICENSE mentions Apache passes cleanly on a file that grants
     * FSL. Measured, not reasoned about — the one-sided version of this check was written first and
     * a full FSL body pasted into an Apache-2.0 module went green.
     */
    private static final Map<String, String> GRANTS = Map.of(
            "Apache-2.0", "Licensed under the Apache License, Version 2.0",
            "FSL-1.1-ALv2", "Licensed under the Functional Source License");

    @Test
    void everyModuleCarriesItsOwnCorrectlyNamedLicence() {
        List<String> problems = new ArrayList<>();
        String licensing = read(repoRoot().resolve("LICENSING.md"));

        for (String module : modules()) {
            Path licence = repoRoot().resolve(module).resolve("LICENSE");
            if (!Files.isRegularFile(licence)) {
                problems.add(module + ": no LICENSE file (ADR-021 licenses per module)");
                continue;
            }
            String text = read(licence);
            String firstLine = text.lines().findFirst().orElse("");
            String expected = "Code Spire — " + module;
            if (!expected.equals(firstLine)) {
                problems.add(module + "/LICENSE line 1 is \"" + firstLine + "\", expected \""
                        + expected + "\" — copied from another module and not renamed");
            }

            String declared = declaredLicenceOf(module, licensing);
            if (declared == null) {
                problems.add(module + ": no row in LICENSING.md");
                continue;
            }
            String grant = GRANTS.get(declared);
            if (grant == null) {
                problems.add(module + ": LICENSING.md declares \"" + declared
                        + "\", which this check does not know — add it to GRANTS");
                continue;
            }
            if (!text.contains(grant)) {
                problems.add(module + "/LICENSE does not grant \"" + grant
                        + "\", but LICENSING.md declares the module " + declared);
            }
            GRANTS.forEach((otherName, otherGrant) -> {
                if (!otherName.equals(declared) && text.contains(otherGrant)) {
                    problems.add(module + "/LICENSE grants " + otherName + " (\"" + otherGrant
                            + "\") while LICENSING.md declares it " + declared
                            + " — the repository states two incompatible things about this module");
                }
            });
        }

        if (!problems.isEmpty()) {
            fail(report(problems));
        }
    }

    /**
     * Guards the guard. A changed settings format or a wrong repo root would make the check above
     * pass by scanning nothing — the one failure mode of a check like this that nobody notices.
     */
    @Test
    void theScanReachesEveryModule() {
        List<String> modules = modules();

        assertTrue(modules.size() >= 15,
                "expected every module in settings.gradle.kts (~20), found " + modules.size()
                        + " — has the include() syntax changed?");
        assertTrue(modules.contains("spire-contract"), "spire-contract was not among " + modules);
    }

    /**
     * The licence LICENSING.md declares for a module, or null when it has no row. A row's first
     * cell is the module and its second the licence, either optionally wrapped in bold markers.
     */
    private static String declaredLicenceOf(String module, String licensing) {
        for (String line : licensing.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            List<String> cells = splitOnPipes(trimmed);
            if (cells.size() < 3) {
                continue;
            }
            if (module.equals(unadorn(cells.get(1)))) {
                return unadorn(cells.get(2));
            }
        }
        return null;
    }

    private static List<String> splitOnPipes(String row) {
        List<String> cells = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == '|') {
                cells.add(row.substring(start, i));
                start = i + 1;
            }
        }
        cells.add(row.substring(start));
        return cells;
    }

    /** A markdown table cell without its surrounding whitespace, bold markers or code ticks. */
    private static String unadorn(String cell) {
        String value = cell.strip();
        while (value.startsWith("*")) {
            value = value.substring(1);
        }
        while (value.endsWith("*")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.replace('`', ' ').strip();
    }

    private static List<String> modules() {
        List<String> modules = new ArrayList<>();
        String prefix = "include(\"";
        for (String line : read(repoRoot().resolve("settings.gradle.kts")).lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            int end = trimmed.indexOf('"', prefix.length());
            if (end > 0) {
                modules.add(trimmed.substring(prefix.length(), end));
            }
        }
        return modules;
    }

    private static String report(List<String> problems) {
        return problems.size() + " module licensing problem(s):\n\n  "
                + String.join("\n  ", problems)
                + """


                Each module states its own licence (ADR-021, LICENSING.md), and third parties rely
                on that statement when deciding whether they may build a plugin against it. When
                adding a module, copy the LICENSE of one carrying the SAME licence, change the first
                line, and check you did not inherit a closing paragraph that is true only of the
                module you copied from.
                """;
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
