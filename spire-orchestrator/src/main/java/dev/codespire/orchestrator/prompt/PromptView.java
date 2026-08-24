package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptVariable;

import java.time.Instant;
import java.util.List;

/**
 * A prompt kind as the API returns it: the effective (custom-or-default) text, whether it is
 * customized, the variable palette, and the read-only locked system suffix (security clause +
 * output contract) so the UI can show what always gets appended.
 *
 * <p>{@code baseKnown} and {@code defaultDrifted} are not interchangeable: {@code baseKnown=false}
 * means the row predates ancestor tracking, so drift is unknowable rather than false. When
 * {@code baseKnown} is true, {@code baseSystem}/{@code baseBody} are the ancestor recorded at last
 * save and {@code currentDefaultSystem}/{@code currentDefaultBody} are what ships now, so the UI can
 * show both sides of the diff. See {@link PromptRegistry.Drift}.
 */
public record PromptView(String kind, boolean customized, String system, String body,
                         Instant updatedAt, List<PromptVariable> palette, String lockedSuffixPreview,
                         boolean baseKnown, boolean defaultDrifted,
                         String currentDefaultSystem, String currentDefaultBody,
                         String baseSystem, String baseBody) {
}
