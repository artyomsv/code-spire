package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PromptRegistryTest {

    @Inject
    PromptRegistry registry;

    @Test
    void effectiveFallsBackToDefaultWhenNotCustomized() {
        registry.reset(PromptKind.RECONCILE);
        PromptView view = registry.effective(PromptKind.RECONCILE);
        assertFalse(view.customized());
        assertFalse(view.body().isBlank());
        assertTrue(view.lockedSuffixPreview().contains("\"verdicts\""));
    }

    @Test
    void savedTemplateIsReturnedAndResettable() {
        registry.save(PromptKind.REVIEW, "custom sys", "custom body {{diff}}");
        PromptView view = registry.effective(PromptKind.REVIEW);
        assertTrue(view.customized());
        assertEquals("custom body {{diff}}", view.body());
        assertTrue(registry.customized(PromptKind.REVIEW).isPresent());

        assertTrue(registry.reset(PromptKind.REVIEW));
        assertFalse(registry.effective(PromptKind.REVIEW).customized());
        assertFalse(registry.reset(PromptKind.REVIEW)); // already gone
    }
}
