package dev.codespire.contract.llm;

/**
 * The one truncation marker every context clipper appends when it cuts text short to fit the
 * model's context window — {@code spire-diff}'s {@code TokenBudget} (diff hunks) and
 * {@code spire-context-code}'s {@code SnippetExtractor} (extracted code snippets) both reference
 * this constant rather than each holding their own copy, so the text can never drift between them.
 * Both modules already depend on {@code spire-contract}, so this costs no new dependency edge.
 */
public final class PromptClipping {

    /** Three ASCII dots, not an ellipsis character — deliberately, so it survives any encoding. */
    public static final String TRUNCATION_MARKER = "\n...(truncated to fit the model context)";

    private PromptClipping() {
    }
}
