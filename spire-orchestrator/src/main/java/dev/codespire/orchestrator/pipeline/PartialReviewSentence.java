package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ClippedInputs;
import dev.codespire.contract.review.ReviewResult;

import java.util.List;

/**
 * The dashboard note of a review that cut something to fit: which files the model did not fully see (review input
 * limit, 2026-11-26), and which background inputs were cut (context cut design, 2026-11-27). Lists as many paths as
 * the posted summary does, then a count, so the dashboard and the pull request agree. Moved out of
 * {@link ResultSaga}, which already held more than a reader can.
 */
final class PartialReviewSentence {

    /** As many paths as the posted summary lists. */
    private static final int MAX_LISTED_OMITTED = 20;

    private PartialReviewSentence() {
    }

    /** The sentence for a truncated result, or null when nothing was cut. */
    static String of(ReviewResult result) {
        if (!result.truncated()) {
            return null;
        }
        String background = ClippedInputs.backgroundInWords(result);
        if (!result.diffNotWhole()) {
            return "Some background did not fit the review budget and was cut: " + background
                    + ". Every changed line was reviewed, so the verdict was not held.";
        }
        String diff = diffSentence(result);
        return background == null ? diff : diff + " Also cut to fit: " + background + ".";
    }

    private static String diffSentence(ReviewResult result) {
        if (!result.omittedFiles().isEmpty()) {
            return namingFiles(result.omittedFiles());
        }
        if (result.clippedInputs().isEmpty()) {
            // A result written before the cut inputs were named: what was cut is unknown.
            return "Some review input exceeded its budget — partial review (what did not fit was not reviewed).";
        }
        return "Diff exceeded the review input limit and its end was cut — partial review (the changes past the cut "
                + "were not reviewed).";
    }

    private static String namingFiles(List<String> omittedFiles) {
        int listed = Math.min(MAX_LISTED_OMITTED, omittedFiles.size());
        StringBuilder note = new StringBuilder("Diff exceeded the review input limit (the model's input window or ")
                .append("the Review diff limit setting) — partial review. The model did not fully see ")
                .append(omittedFiles.size()).append(omittedFiles.size() == 1 ? " file: " : " files: ")
                .append(String.join(", ", omittedFiles.subList(0, listed)));
        int more = omittedFiles.size() - listed;
        if (more > 0) {
            note.append(" and ").append(more).append(" more");
        }
        return note.append('.').toString();
    }
}
