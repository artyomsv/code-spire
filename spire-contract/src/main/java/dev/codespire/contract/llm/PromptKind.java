package dev.codespire.contract.llm;

/** The three operator-editable prompt kinds. {@code slug} is the API/DB key. */
public enum PromptKind {
    REVIEW("review"),
    RECONCILE("reconcile"),
    FOLLOWUP("followup");

    private final String slug;

    PromptKind(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static PromptKind fromSlug(String slug) {
        for (PromptKind kind : values()) {
            if (kind.slug.equals(slug)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown prompt kind '" + slug
                + "' (expected one of: review, reconcile, followup)");
    }
}
