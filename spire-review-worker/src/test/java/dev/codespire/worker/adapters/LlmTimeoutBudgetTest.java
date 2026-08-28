package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LLM budget must stay clear of the Kafka ack threshold.
 *
 * <p>The pairing has no single owner: one value is a {@code spire.*} property and the other belongs
 * to a SmallRye channel, so nothing but this check relates them. When they were equal (both 60s), a
 * review that used its full budget went unacknowledged, failed the {@code commands-in} channel, and
 * stalled every later command — recoverable only by seeking the consumer group past the record.
 */
class LlmTimeoutBudgetTest {

    @Test
    void aThresholdWithHeadroomForEveryCallIsAccepted() {
        assertNull(LlmTimeoutBudget.refusalFor(180, 900_000L));
    }

    @Test
    void theShippedDefaultsAreSafe() {
        // Guards the pairing as CONFIGURED, not merely as computed: the numbers live in two files
        // (application.yml and this class's defaults) that nothing else forces to agree.
        assertNull(LlmTimeoutBudget.refusalFor(180, ackMaxAgeFromApplicationYaml()),
                "the shipped application.yml threshold must clear the shipped LLM budget");
    }

    @Test
    void aThresholdEqualToTheBudgetIsRefused() {
        // The boundary case, and the one that actually shipped: 60s of LLM budget against SmallRye's
        // 60000ms default. Equal is not enough — the record is acked AFTER the call returns.
        assertNotNull(LlmTimeoutBudget.refusalFor(60, 60_000L));
    }

    @Test
    void aThresholdEqualToTheWholeCommandBudgetIsStillRefused() {
        long exactly = 180L * 1000L * LlmTimeoutBudget.LLM_CALLS_PER_COMMAND;
        assertNotNull(LlmTimeoutBudget.refusalFor(180, exactly),
                "the threshold must EXCEED the command budget, not merely match it");
        assertNull(LlmTimeoutBudget.refusalFor(180, exactly + 1));
    }

    @Test
    void aReconcileAndReviewPairIsBudgetedFor() {
        // ADR-019 runs two paid calls inside one GenerateReview. A threshold sized for one of them
        // looks generous and still stalls, which is why the multiplier exists.
        assertNotNull(LlmTimeoutBudget.refusalFor(180, 200_000L),
                "room for one call is not room for the reconcile+review pair");
    }

    @Test
    void theRefusalNamesBothSettingsAndTheNumberToBeat() {
        String refusal = LlmTimeoutBudget.refusalFor(60, 60_000L);
        assertNotNull(refusal);
        assertTrue(refusal.contains("SPIRE_KAFKA_ACK_MAX_AGE_MS"), "names the variable to change");
        assertTrue(refusal.contains("spire.llm.timeout-seconds"), "names the other half of the pairing");
        assertTrue(refusal.contains("120000"), "names the value the threshold must beat");
    }

    @Test
    void aNonPositiveBudgetIsRejected() {
        assertThrows(IllegalStateException.class, () -> LlmTimeoutBudget.refusalFor(0, 900_000L));
        assertThrows(IllegalStateException.class, () -> LlmTimeoutBudget.refusalFor(-1, 900_000L));
    }

    @Test
    void theBudgetIsExposedAsADuration() {
        LlmTimeoutBudget budget = new LlmTimeoutBudget();
        budget.timeoutSeconds = 42;
        assertEquals(42, budget.timeout().toSeconds());
    }

    /** Reads the real deployment descriptor rather than restating its number here. */
    @SuppressWarnings("unchecked")
    private static long ackMaxAgeFromApplicationYaml() {
        try (InputStream in = LlmTimeoutBudgetTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml must be on the test classpath");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> node = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    ((Map<String, Object>) root.get("mp")).get("messaging")).get("incoming")).get("commands-in");
            Map<String, Object> throttled = (Map<String, Object>) node.get("throttled");
            assertNotNull(throttled, "commands-in must set throttled.unprocessed-record-max-age.ms");
            Map<String, Object> maxAge = (Map<String, Object>) throttled.get("unprocessed-record-max-age");
            String raw = String.valueOf(maxAge.get("ms"));
            // ${SPIRE_KAFKA_ACK_MAX_AGE_MS:900000} — the default is what an operator who sets nothing gets.
            int colon = raw.lastIndexOf(':');
            return Long.parseLong(colon < 0 ? raw : raw.substring(colon + 1).replace("}", "").trim());
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped ack threshold", e);
        }
    }
}
