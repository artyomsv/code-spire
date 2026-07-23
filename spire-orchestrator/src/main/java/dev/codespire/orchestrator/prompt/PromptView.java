package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptVariable;

import java.time.Instant;
import java.util.List;

/**
 * A prompt kind as the API returns it: the effective (custom-or-default) text, whether it is
 * customized, the variable palette, and the read-only locked system suffix (security clause +
 * output contract) so the UI can show what always gets appended.
 */
public record PromptView(String kind, boolean customized, String system, String body,
                         Instant updatedAt, List<PromptVariable> palette, String lockedSuffixPreview) {
}
