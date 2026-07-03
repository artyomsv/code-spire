package dev.codespire.contract.llm;

/**
 * A rendered prompt. Untrusted content (diffs, PR text, retrieved context) is
 * fenced/labeled inside {@code user} as data-not-instructions; {@code system}
 * is never assembled from untrusted content (SECURITY.md, LLM threat model).
 */
public record Prompt(String system, String user) {
}
