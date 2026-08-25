package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.scm.RepoRef;
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

    /**
     * The override to attach to a command, most specific first: the repository's, else the global
     * one, else null so the worker uses the built-in default (no command bloat in the common case).
     */
    public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
        return registry.customized(kind, PromptScope.of(repo))
                .or(() -> registry.customized(kind, PromptScope.GLOBAL))
                .orElse(null);
    }
}
