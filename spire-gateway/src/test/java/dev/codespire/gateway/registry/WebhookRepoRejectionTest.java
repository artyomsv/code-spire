package dev.codespire.gateway.registry;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rejected delivery is stored as STATE on the row it concerns, not as a log entry, which is
 * what lets a successful delivery clear it. Without the clearing half, this would be an
 * incident log wearing a condition's clothes and would never stop nagging.
 */
@QuarkusTest
class WebhookRepoRejectionTest {

    @Inject
    WebhookRepoRegistry registry;

    private WebhookRepoSecret register(String target) {
        return registry.create(new WebhookRepoInput("stub", "repo", target, true));
    }

    @Test
    void aFreshRegistrationIsNotRejecting() {
        register("TEST-OWNER/TEST-REPO-fresh");
        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("fresh")));
    }

    @Test
    void aRecordedRejectionIsReportedWithItsReasonAndCount() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-bad");
        String key = created.repo().webhookKey();

        registry.recordRejection(key, "bad_signature");
        registry.recordRejection(key, "bad_signature");

        WebhookRepoRegistry.Rejection row = registry.rejecting().stream()
                .filter(r -> r.target().endsWith("bad")).findFirst().orElseThrow();
        assertEquals("bad_signature", row.reason());
        assertEquals(2, row.count());
    }

    /** Rotate the secret, next delivery lands, row disappears. This is the self-clearing half. */
    @Test
    void aSuccessfulDeliveryClearsTheRejections() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-recovered");
        String key = created.repo().webhookKey();
        registry.recordRejection(key, "bad_signature");

        registry.clearRejections(key);

        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("recovered")));
    }

    /** Clearing a row that was never rejecting must not write, so it stays cheap on the hot path. */
    @Test
    void clearingACleanRegistrationIsHarmless() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-clean");
        registry.clearRejections(created.repo().webhookKey());
        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("clean")));
    }

    /** An unknown key resolves to no row; there is nothing to attach a counter to. */
    @Test
    void recordingAgainstAnUnknownKeyIsIgnored() {
        registry.recordRejection("TEST-UNKNOWN-KEY", "unknown_key");
        List<WebhookRepoRegistry.Rejection> rows = registry.rejecting();
        assertTrue(rows.stream().noneMatch(r -> "unknown_key".equals(r.reason())));
    }
}
