package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewResult;

import java.util.List;

/**
 * The dashboard note of a partial review: what was cut, and which files the model did not fully see (review input
 * limit, 2026-11-26). Lists as many paths as the posted summary does, then a count, so the dashboard and the pull
 * request agree. Moved out of {@link ResultSaga}, which already held more than a reader can.
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
        if (result.omittedFiles().isEmpty()) {
            return "Diff exceeded the review budget — partial review "
                    + "(changes beyond the token limit were not reviewed).";
        }
        return namingFiles(result.omittedFiles());
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
