package dev.codespire.worker.pipeline;

import java.util.List;

/**
 * The "Partial review" note at the top of a posted summary. A clipped diff names the files the model did not fully
 * see, so a partial review never reads as a complete one (review input limit design, 2026-11-26).
 */
final class PartialReviewNote {

    /** Past this many paths the note says how many more, so a huge pull request does not bury the review. */
    static final int MAX_LISTED = 20;

    private PartialReviewNote() {
    }

    static String markdown(List<String> omittedFiles) {
        if (omittedFiles.isEmpty()) {
            // A clipped context, or a result written before files were named.
            return "> **Partial review:** some of this review's input exceeded its budget and was "
                    + "truncated — what did not fit was not reviewed.\n\n";
        }
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
        return note.append('\n').toString();
    }
}
