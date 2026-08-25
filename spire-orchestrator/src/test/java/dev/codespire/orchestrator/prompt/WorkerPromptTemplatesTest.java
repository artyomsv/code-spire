package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class WorkerPromptTemplatesTest {

    @Inject
    WorkerPromptTemplates templates;

    @Inject
    PromptRegistry registry;

    @Test
    void packsTheRepoOverrideWhenOneExists() {
        // Both scopes customized, with conflicting content: proves the repo row wins rather than
        // merely being the only thing found (a global-first resolver would return "Global persona").
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        assertEquals("Repo persona",
                templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "widgets")).system());

        registry.reset(PromptKind.REVIEW);
        registry.reset(PromptKind.REVIEW, "acme/widgets");
    }

    @Test
    void fallsBackToGlobalForARepoWithNoOverride() {
        registry.reset(PromptKind.REVIEW);
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

        assertEquals("Global persona",
                templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "other")).system());

        registry.reset(PromptKind.REVIEW);
    }

    @Test
    void packsNothingWhenNeitherScopeIsCustomized() {
        registry.reset(PromptKind.REVIEW);
        registry.reset(PromptKind.REVIEW, "acme/widgets");

        // null keeps the common case off the command entirely: the worker uses the built-in default.
        assertNull(templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "widgets")));
    }
}
