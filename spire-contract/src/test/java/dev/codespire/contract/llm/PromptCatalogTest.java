package dev.codespire.contract.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCatalogTest {

    @Test
    void everyKindHasADefaultTemplateAndPalette() {
        for (PromptKind kind : PromptKind.values()) {
            PromptTemplate def = PromptCatalog.defaultTemplate(kind);
            assertEquals(kind, def.kind());
            assertTrue(!def.system().isBlank(), "system for " + kind);
            assertTrue(!def.body().isBlank(), "body for " + kind);
            assertTrue(!PromptCatalog.palette(kind).isEmpty(), "palette for " + kind);
        }
    }

    @Test
    void defaultBodyReferencesOnlyKnownVariablesAndAllRequiredOnes() {
        for (PromptKind kind : PromptKind.values()) {
            List<String> errors = PromptValidation.validate(
                    kind, PromptCatalog.defaultTemplate(kind).system(),
                    PromptCatalog.defaultTemplate(kind).body());
            assertEquals(List.of(), errors, "default template for " + kind + " must be valid");
        }
    }

    @Test
    void lockedSuffixCarriesSecurityClauseAndOutputContract() {
        String review = PromptCatalog.lockedSystemSuffix(PromptKind.REVIEW);
        assertTrue(review.contains("BEGIN_UNTRUSTED_DATA"), "security clause");
        assertTrue(review.contains("\"findings\""), "review JSON contract");
        assertTrue(PromptCatalog.lockedSystemSuffix(PromptKind.RECONCILE).contains("\"verdicts\""));
        assertTrue(PromptCatalog.lockedSystemSuffix(PromptKind.FOLLOWUP).toLowerCase().contains("plain-text"));
    }

    @Test
    void reviewRequiresDiff() {
        PromptVariable diff = PromptCatalog.palette(PromptKind.REVIEW).stream()
                .filter(v -> v.name().equals("diff")).findFirst().orElseThrow();
        assertTrue(diff.required());
        assertTrue(diff.fenced());
    }

    @Test
    void diffKindIsTheOnlyLiteral() {
        for (PromptKind kind : PromptKind.values()) {
            for (PromptVariable v : PromptCatalog.palette(kind)) {
                assertEquals(v.name().equals("diff_kind"), !v.fenced(),
                        v.name() + " fenced flag");
            }
        }
    }
}
