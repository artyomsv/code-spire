package dev.codespire.orchestrator.prompt;

/** Create/update payload for a prompt override. */
public record PromptInput(String system, String body) {
}
