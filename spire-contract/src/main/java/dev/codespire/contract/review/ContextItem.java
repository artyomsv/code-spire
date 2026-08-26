package dev.codespire.contract.review;

/** kind: JIRA_TICKET | CONFLUENCE_PAGE | ISSUE | PULL_REQUEST | EPIC | RULE | CODE_SNIPPET | MEMORY_NOTE. */
public record ContextItem(String kind, String title, String body, String uri) {

    /**
     * The one spelling of the retrieved-source kind, shared because three modules act on it and each
     * acted on its own copy of the literal.
     *
     * <p>{@code CodeContextProvider} (spire-context-code) emits it, {@code ContextWorker}
     * (spire-review-worker) excludes it from level-2 reference mining — a {@code PROJ-123} sitting in
     * a source comment must never become a live ticket fetch — and {@code ReviewPromptBuilder}
     * (spire-llm) routes it to the dedicated {@code {{code_context}}} prompt slot rather than the
     * shared ticket budget. All three already depend on this module. With three unshared literals, a
     * rename on the emitting side silently switched off both of the other two behaviours at once, and
     * every test asserted against its own copy of the string, so nothing could fail on the break.
     */
    public static final String CODE_SNIPPET = "CODE_SNIPPET";
}
