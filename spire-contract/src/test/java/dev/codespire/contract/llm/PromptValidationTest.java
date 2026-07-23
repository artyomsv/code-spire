package dev.codespire.contract.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptValidationTest {

    @Test
    void unknownVariableIsRejected() {
        List<String> errors = PromptValidation.validate(PromptKind.REVIEW,
                "instructions", "review this {{diff}} and {{banana}}");
        assertTrue(errors.stream().anyMatch(e -> e.contains("banana")), errors.toString());
    }

    @Test
    void missingRequiredVariableIsRejected() {
        List<String> errors = PromptValidation.validate(PromptKind.REVIEW,
                "instructions", "no diff here, just {{pr_title}}");
        assertTrue(errors.stream().anyMatch(e -> e.contains("diff")), errors.toString());
    }

    @Test
    void variableInSystemIsRejected() {
        List<String> errors = PromptValidation.validate(PromptKind.REVIEW,
                "you review {{diff}}", "{{diff}}");
        assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("system")), errors.toString());
    }

    @Test
    void validTemplatePasses() {
        assertEquals(List.of(), PromptValidation.validate(PromptKind.REVIEW,
                "you are a reviewer", "review {{pr_title}} and {{diff}}"));
    }

    @Test
    void previewAnnotatesSlotsAndAppendsLockedSuffix() {
        PromptValidation.PromptPreview p = PromptValidation.preview(PromptKind.REVIEW,
                "you are a reviewer", "review {{diff}}");
        assertTrue(p.user().contains("«diff"), p.user());
        assertTrue(p.system().contains("you are a reviewer"));
        assertTrue(p.system().contains("BEGIN_UNTRUSTED_DATA"), "locked suffix in system");
    }
}
