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
 * Which files of a line-clipped diff the model did not fully see. RECONCILE clips its diff string at a line, not at a
 * file, so the files are recovered from the text: a git compare diff through {@link UnifiedDiffParser}, the rendered
 * full-diff fallback through its {@code ## File:} headers.
 */
final class DiffCoverage {

    private static final Pattern RENDERED_HEADER = Pattern.compile("(?m)^## File: '(.*)' \\([A-Z_]+\\)$");

    private DiffCoverage() {
    }

    /**
     * @param full the diff before clipping
     * @param kept what {@link dev.codespire.diff.TokenBudget#clip} kept of it, marker included or not
     * @return the paths not fully inside {@code kept}, in diff order
     */
    static List<String> omittedPaths(String full, String kept) {
        String prefix = kept.endsWith(PromptClipping.TRUNCATION_MARKER)
                ? kept.substring(0, kept.length() - PromptClipping.TRUNCATION_MARKER.length())
                : kept;
        List<FilePatch> all = UnifiedDiffParser.parse(full);
        if (all.isEmpty()) {
            return renderedOmitted(full, prefix.length());
        }
        return unifiedOmitted(all, prefix);
    }

    /**
     * The last file the clipped text starts is cut inside, or may be, so it counts as omitted. A cut section the
     * parser cannot read moves that mark one file early, which errs toward naming a file the model saw rather than
     * passing over one it did not.
     */
    private static List<String> unifiedOmitted(List<FilePatch> all, String prefix) {
        List<FilePatch> seen = UnifiedDiffParser.parse(prefix);
        Set<String> fullySeen = new LinkedHashSet<>();
        for (int i = 0; i < seen.size() - 1; i++) {
            fullySeen.add(DiffSelection.displayPath(seen.get(i)));
        }
        Set<String> omitted = new LinkedHashSet<>();
        for (FilePatch patch : all) {
            String path = DiffSelection.displayPath(patch);
            if (path != null && !fullySeen.contains(path)) {
                omitted.add(path);
            }
        }
        return new ArrayList<>(omitted);
    }

    /** Exact for the rendered format: a file is seen when its last character is inside the kept prefix. */
    private static List<String> renderedOmitted(String full, int keptLength) {
        Matcher matcher = RENDERED_HEADER.matcher(full);
        List<Integer> starts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            paths.add(matcher.group(1));
        }
        List<String> omitted = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : full.length();
            while (end > starts.get(i) && full.charAt(end - 1) == '\n') {
                end--;
            }
            if (end > keptLength) {
                omitted.add(paths.get(i));
            }
        }
        return omitted;
    }
}
