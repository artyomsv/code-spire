package dev.codespire.orchestrator.memory;

import java.util.List;
import java.util.Locale;

/**
 * Turns a file path into the group a learned preference is about (P4 / FR-10).
 *
 * <p><b>A fixed ladder, not judgement.</b> Group identity has to be recognisable tomorrow, because
 * {@code learned_preference} remembers a rejected group and must not propose it again every night. A
 * rule that sometimes generalises further would make the identity depend on the corpus's shape on the
 * night it ran, so a rejected proposal would silently return under a different name.
 *
 * <p>Deliberately coarse at the bottom: a glob that resolves to a single file is not a preference,
 * it is an opinion about one line of code.
 */
final class PathGlobs {

    /** Directory names that mean "this is test code" across the languages this reviews. */
    private static final List<String> TEST_DIRECTORIES = List.of("test", "tests", "spec", "__tests__");

    private static final List<String> TEST_FILE_MARKERS = List.of(".test.", ".spec.");

    /** How many leading segments a fallback glob keeps. Two is a module, one is usually everything. */
    private static final int FALLBACK_SEGMENTS = 2;

    private PathGlobs() {
    }

    /** @return the glob this path belongs to, or null when the path is unusable. */
    static String of(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");

        for (String segment : segments) {
            if (TEST_DIRECTORIES.contains(segment.toLowerCase(Locale.ROOT))) {
                return "**/" + segment.toLowerCase(Locale.ROOT) + "/**";
            }
        }

        String fileName = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        for (String marker : TEST_FILE_MARKERS) {
            if (fileName.contains(marker)) {
                return "**/*" + marker + "*";
            }
        }

        if (segments.length <= 1) {
            // A file at the repository root. Its own name is the only thing that identifies it, and a
            // preference about one file is not a preference — so this groups with nothing.
            return null;
        }
        int keep = Math.min(FALLBACK_SEGMENTS, segments.length - 1);
        return String.join("/", List.of(segments).subList(0, keep)) + "/**";
    }

    /**
     * Whether a path falls under a glob produced by {@link #of}.
     *
     * <p>Matching goes through the same ladder rather than through a glob engine: the only globs that
     * can exist are the ones this class produces, so re-deriving is exact and cannot drift from
     * whatever a general matcher would have decided.
     */
    static boolean matches(String glob, String path) {
        String derived = of(path);
        return derived != null && derived.equals(glob);
    }
}
