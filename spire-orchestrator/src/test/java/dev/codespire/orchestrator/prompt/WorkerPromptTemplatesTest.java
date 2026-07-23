package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class WorkerPromptTemplatesTest {

    @Inject
    WorkerPromptTemplates templates;

    @Inject
    PromptRegistry registry;

    @Test
    void nullWhenNotCustomized() {
        registry.reset(PromptKind.REVIEW);
        assertNull(templates.forKind(PromptKind.REVIEW));
    }

    @Test
    void returnsCustomTemplateWhenSet() {
        registry.save(PromptKind.REVIEW, "sys", "review {{diff}}");
        assertEquals("review {{diff}}", templates.forKind(PromptKind.REVIEW).body());
        registry.reset(PromptKind.REVIEW);
    }
}
