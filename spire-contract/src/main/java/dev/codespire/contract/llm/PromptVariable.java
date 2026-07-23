package dev.codespire.contract.llm;

/**
 * A variable an operator may place in a prompt body. {@code fenced} variables carry
 * author-influenced text and are wrapped in BEGIN/END_UNTRUSTED_DATA and sentinel-neutralized;
 * {@code maxTokens} is the clip cap (0 = no clip). A non-fenced variable is an engine-computed
 * literal (only {@code diff_kind}).
 */
public record PromptVariable(String name, boolean required, boolean fenced, int maxTokens, String description) {
}
