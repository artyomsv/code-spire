package dev.codespire.llm;

import dev.codespire.contract.scm.FilePatch;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.diff.TokenBudget;

import java.util.ArrayList;
import java.util.List;

/**
 * A REVIEW diff cut to its budget at file boundaries, and the files the model will not fully see.
 *
 * <p>Files keep their order. A file that does not fit in what is left is skipped, and later smaller files still go
 * in, so one huge lock file does not hide every file after it. Only when no whole file fits is the first file clipped
 * inside, at a line boundary, as {@link TokenBudget#clip} always did; it is named as omitted too, since the model saw
 * only its start.
 *
 * @param text         the rendered diff to send
 * @param omittedFiles the paths skipped or clipped, in diff order
 */
public record DiffSelection(String text, List<String> omittedFiles) {

    public DiffSelection {
        omittedFiles = List.copyOf(omittedFiles);
    }

    public static DiffSelection byFile(List<FilePatch> patches, int budget) {
        StringBuilder kept = new StringBuilder();
        List<String> omitted = new ArrayList<>();
        String firstSkipped = null;
        long used = 0;
        for (FilePatch patch : patches) {
            String text = DiffRenderer.render(List.of(patch));
            if (text.isEmpty()) {
                continue; // a binary file renders nothing, so there is nothing to send or to miss
            }
            int cost = TokenBudget.estimateTokens(text);
            if (used + cost <= budget) {
                kept.append(text);
                used += cost;
                continue;
            }
            omitted.add(displayPath(patch));
            if (firstSkipped == null) {
                firstSkipped = text;
            }
        }
        if (kept.isEmpty() && firstSkipped != null) {
            return new DiffSelection(TokenBudget.clip(firstSkipped, budget), omitted);
        }
        return new DiffSelection(kept.toString(), omitted);
    }

    /** The path the rendered diff shows for a file, as {@link DiffRenderer} names it. */
    static String displayPath(FilePatch patch) {
        return patch.newPath() != null ? patch.newPath() : patch.oldPath();
    }
}
