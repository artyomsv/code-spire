package dev.codespire.orchestrator.factory;

import io.smallrye.reactive.messaging.annotations.Blocking;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache keeps the LAST answer per harness, so the answers must be recorded in the order they arrive.
 * The worker keys them by harness; that order survives only if they are also recorded one at a time
 * (second review of PR #167).
 */
class HarnessImageResultsOrderTest {

    /**
     * An answer sent while the orchestrator's group was still being assigned, after a restart, must
     * not be skipped (measured on the dev stack, 2026-09-23).
     */
    @Test
    void anAnswerSentBeforeTheOrchestratorJoinedIsStillRecorded() throws java.io.IOException {
        assertTrue(channel("harness-image-results-in").contains("reset: earliest"));
    }

    /** A sign-in result lost the same way left the screen waiting for a code already printed. */
    @Test
    void aSignInResultSentBeforeTheOrchestratorJoinedIsStillRecorded() throws java.io.IOException {
        assertTrue(channel("harness-sign-in-results-in").contains("reset: earliest"));
    }

    /** One channel's settings, up to its failure strategy. */
    private static String channel(String name) throws java.io.IOException {
        String yaml;
        try (var in = HarnessImageResults.class.getResourceAsStream("/application.yml")) {
            yaml = new String(java.util.Objects.requireNonNull(in, "application.yml").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = yaml.indexOf("      " + name + ":");
        int end = yaml.indexOf("failure-strategy", start);
        assertTrue(start >= 0 && end > start, name + " is not declared");
        return yaml.substring(start, end);
    }

    /** The first tick waits: at startup it ran before the Kafka emitter was connected. */
    @Test
    void theRefreshTimerDoesNotFireDuringStartup() throws NoSuchMethodException {
        var scheduled = HarnessCatalogues.class.getDeclaredMethod("refresh")
                .getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertTrue(!scheduled.delayed().isBlank(), "the first refresh must be delayed");
    }

    @Test
    void theAnswersAreRecordedOneAtATime() throws NoSuchMethodException {
        Blocking blocking = HarnessImageResults.class.getMethod("onResult", Message.class).getAnnotation(Blocking.class);
        assertTrue(blocking.ordered());
    }
}
