package dev.codespire.llmcatalog;

import java.util.Set;

/** The vendor strings the app knows. One list for the catalog validator and every resource that checks a type. */
public final class Vendors {

    public static final Set<String> TYPES = Set.of("openai", "anthropic", "gemini");

    private Vendors() {
    }
}
