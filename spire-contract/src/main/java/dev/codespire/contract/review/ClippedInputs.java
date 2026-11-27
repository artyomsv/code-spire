package dev.codespire.contract.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain words for the prompt inputs a review cut to fit ({@link ReviewResult#clippedInputs}), shared by the note
 * posted on the pull request and the dashboard's, so both say the same thing (context cut design, 2026-11-27).
 */
public final class ClippedInputs {

    private ClippedInputs() {
    }

    /** The background inputs that were cut (all but the diff), in words, joined with commas; null when none was. */
    public static String backgroundInWords(ReviewResult result) {
        List<String> words = new ArrayList<>();
        for (String input : result.clippedInputs()) {
            if (!ReviewResult.DIFF_INPUT.equals(input)) {
                words.add(inWords(input));
            }
        }
        return words.isEmpty() ? null : String.join("; ", words);
    }

    /** One input in words, with what its cut means for the review. */
    static String inWords(String input) {
        return switch (input) {
            case "context" -> "the linked issues and documents (the model read only their start)";
            case "code_context" -> "the definitions of the code this change uses (the model saw fewer of them)";
            case "prior_findings" -> "the list of earlier findings (an earlier finding may be raised again)";
            default -> "the " + input.replace('_', ' ');
        };
    }
}
