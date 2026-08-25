package dev.codespire.orchestrator.prompt;

/**
 * Create/update payload for a prompt override, and the input to a preview.
 *
 * @param reviewId when present, the preview renders against that review's real diff
 *                 ({@link PromptSampleRenderer}) instead of the annotated placeholder;
 *                 {@code null} on every save, which never carries one.
 */
public record PromptInput(String system, String body, String reviewId) {

    /** No sample review — the annotated preview, and every save. */
    public PromptInput(String system, String body) {
        this(system, body, null);
    }
}
