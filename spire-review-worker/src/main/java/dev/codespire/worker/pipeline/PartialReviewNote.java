package dev.codespire.worker.pipeline;

import dev.codespire.contract.review.ClippedInputs;
import dev.codespire.contract.review.ReviewResult;

import java.util.List;

/**
 * The note at the top of a posted summary when something was cut to fit. A cut diff is a "Partial review" and names
 * the files the model did not fully see (review input limit design, 2026-11-26); a cut background input (the linked
 * context, the code definitions, the earlier findings) says what was cut and that every changed line was still
 * reviewed, since it does not hold the verdict (context cut design, 2026-11-27).
 */
final class PartialReviewNote {

    /** Past this many paths the note says how many more, so a huge pull request does not bury the review. */
    static final int MAX_LISTED = 20;

    private PartialReviewNote() {
    }

    /** The note for a truncated result; empty when nothing was cut. */
    static String markdown(ReviewResult result) {
        if (!result.truncated()) {
            return "";
        }
        String background = ClippedInputs.backgroundInWords(result);
        if (!result.diffNotWhole()) {
            return "> **Note:** some background did not fit this review's budget and was cut: " + background
                    + ". Every changed line was reviewed.\n\n";
        }
        String diffNote = diffNote(result);
        if (background == null) {
            return diffNote + "\n";
        }
        return diffNote + ">\n> Also cut to fit: " + background + ".\n\n";
    }

    private static String diffNote(ReviewResult result) {
        if (!result.omittedFiles().isEmpty()) {
            return filesNote(result.omittedFiles());
        }
        if (result.clippedInputs().isEmpty()) {
            // A result written before the cut inputs were named: what was cut is unknown.
            return "> **Partial review:** some of this review's input exceeded its budget and was "
                    + "truncated — what did not fit was not reviewed.\n";
        }
        return "> **Partial review:** this PR's diff exceeded the review input limit and its end was cut, so the "
                + "model did not see every changed line.\n";
    }

    private static String filesNote(List<String> omittedFiles) {
        StringBuilder note = new StringBuilder("> **Partial review:** this PR's diff exceeded the review input "
                + "limit (the model's input window or the Review diff limit setting), so the model did not fully "
                + "see these files:\n>\n");
        int listed = Math.min(MAX_LISTED, omittedFiles.size());
        for (int i = 0; i < listed; i++) {
            // A path is author-supplied: a backtick in it would end the code span and let the rest read as Markdown.
            note.append("> - `").append(omittedFiles.get(i).replace("`", "'")).append("`\n");
        }
        int more = omittedFiles.size() - listed;
        if (more > 0) {
            note.append(">\n> and ").append(more).append(more == 1 ? " more file.\n" : " more files.\n");
        }
        return note.toString();
    }
}
