package dev.codespire.llm;

import dev.codespire.contract.llm.PromptClipping;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.diff.UnifiedDiffParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which files of a RECONCILE diff the model fully saw. RECONCILE clips its diff string at a line, not at a file, so
 * the files are recovered from the text: a git compare diff through {@link UnifiedDiffParser}, the rendered full-diff
 * fallback through its {@code ## File:} headers.
 *
 * <p>Positive, not negative: a clipped diff names the files it is SURE the model saw whole, and every other path,
 * including one in a section no parser could read, counts as unseen. A prior finding may get a verdict other than
 * UNCHANGED from a clipped reconcile only when its file is in that set. Listing the omitted paths instead let a file
 * the parser skipped (a Git C-quoted path, before the parser decoded them) pass as seen.
 */
public final class DiffCoverage {

    private static final Pattern RENDERED_HEADER = Pattern.compile("(?m)^## File: '(.*)' \\([A-Z_]+\\)$");

    private static final DiffCoverage WHOLE = new DiffCoverage(null, List.of());

    /** The paths positively seen whole, or null when nothing was cut. */
    private final Set<String> fullySeen;
    private final List<String> omittedFiles;

    private DiffCoverage(Set<String> fullySeen, List<String> omittedFiles) {
        this.fullySeen = fullySeen == null ? null : Set.copyOf(fullySeen);
        this.omittedFiles = List.copyOf(omittedFiles);
    }

    /** The whole diff was sent. */
    public static DiffCoverage whole() {
        return WHOLE;
    }

    /**
     * @param full the diff before clipping
     * @param kept what {@link dev.codespire.diff.TokenBudget#clip} kept of it, marker included or not
     */
    public static DiffCoverage clipped(String full, String kept) {
        String prefix = kept.endsWith(PromptClipping.TRUNCATION_MARKER)
                ? kept.substring(0, kept.length() - PromptClipping.TRUNCATION_MARKER.length())
                : kept;
        List<FilePatch> all = UnifiedDiffParser.parse(full);
        Set<String> seen = all.isEmpty() ? renderedSeen(full, prefix.length()) : unifiedSeen(prefix);
        List<String> named = all.isEmpty() ? renderedPaths(full) : displayPaths(all);
        List<String> omitted = new ArrayList<>();
        for (String path : named) {
            if (!seen.contains(path)) {
                omitted.add(path);
            }
        }
        return new DiffCoverage(seen, omitted);
    }

    /** Whether the diff was cut at all. */
    public boolean isClipped() {
        return fullySeen != null;
    }

    /** Whether the model saw every line this diff has for the path: always, when nothing was cut. */
    public boolean sawWhole(String path) {
        return fullySeen == null || fullySeen.contains(path);
    }

    /** The paths of the diff not positively seen whole, in diff order; a path no parser could read is not named. */
    public List<String> omittedFiles() {
        return omittedFiles;
    }

    /**
     * A file section is whole when another section starts after it inside the kept text, so every section but the
     * last one parsed from the kept text. The last may be cut anywhere, even at its very end, so it never counts.
     */
    private static Set<String> unifiedSeen(String prefix) {
        List<FilePatch> parsed = UnifiedDiffParser.parse(prefix);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < parsed.size() - 1; i++) {
            String path = DiffSelection.displayPath(parsed.get(i));
            if (path != null) {
                seen.add(path);
            }
        }
        return seen;
    }

    private static List<String> displayPaths(List<FilePatch> patches) {
        Set<String> paths = new LinkedHashSet<>();
        for (FilePatch patch : patches) {
            String path = DiffSelection.displayPath(patch);
            if (path != null) {
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    /** Exact for the rendered format: a file is seen when its last character is inside the kept prefix. */
    private static Set<String> renderedSeen(String full, int keptLength) {
        Matcher matcher = RENDERED_HEADER.matcher(full);
        List<Integer> starts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            paths.add(matcher.group(1));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < starts.size(); i++) {
            if (renderedEnd(full, starts, i) <= keptLength) {
                seen.add(paths.get(i));
            }
        }
        return seen;
    }

    private static int renderedEnd(String full, List<Integer> starts, int index) {
        int end = index + 1 < starts.size() ? starts.get(index + 1) : full.length();
        while (end > starts.get(index) && full.charAt(end - 1) == '\n') {
            end--;
        }
        return end;
    }

    private static List<String> renderedPaths(String full) {
        Matcher matcher = RENDERED_HEADER.matcher(full);
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }
}
