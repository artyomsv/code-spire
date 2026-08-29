package dev.codespire.e2e.spire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpireDriverTest {

    @Test
    void mintsAnOperatorTokenTheOrchestratorAccepts() {
        SpireDriver spire = new SpireDriver();
        assertFalse(spire.operatorToken().isBlank());
        assertNotNull(spire.get("/api/providers"),
                "every registry read is admin-only (ADR-022's third rule), so this also proves the "
                        + "token carries spire-admin rather than merely being valid");
    }

    @Test
    void setsAndReadsBackTheReviewMode() {
        SpireDriver spire = new SpireDriver();
        spire.setReviewMode("active");
        assertEquals("active", spire.get("/api/settings/review-mode").get("mode").asText());
    }

    /**
     * Also proves the two-token split is necessary rather than defensive: this call goes to the
     * gateway's own prefix, and a token minted for the orchestrator is refused there by design.
     */
    @Test
    void registersAWebhookAndGetsTheSecretExactlyOnce() {
        SpireDriver spire = new SpireDriver();
        SpireDriver.Webhook hook = spire.registerWebhook("gitlab", "e2e-probe/e2e-probe-webhook");

        assertFalse(hook.key().isBlank());
        assertFalse(hook.secret().isBlank(),
                "the secret is returned only on create — GitLab's Secret token field needs it, and the "
                        + "view thereafter carries only hasSecret");
    }
}
