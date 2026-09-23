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

    @Test
    void theAnswersAreRecordedOneAtATime() throws NoSuchMethodException {
        Blocking blocking = HarnessImageResults.class.getMethod("onResult", Message.class).getAnnotation(Blocking.class);
        assertTrue(blocking.ordered());
    }
}
