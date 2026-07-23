package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the operator override for a prompt kind to attach onto a command — the prompt analog of
 * {@link dev.codespire.orchestrator.llm.WorkerLlmCredentials}. Returns {@code null} when the kind is
 * not customized, so the worker falls back to the built-in default (no command bloat, common case).
 */
@ApplicationScoped
public class WorkerPromptTemplates {

    @Inject
    PromptRegistry registry;

    public PromptTemplate forKind(PromptKind kind) {
        return registry.customized(kind).orElse(null);
    }
}
