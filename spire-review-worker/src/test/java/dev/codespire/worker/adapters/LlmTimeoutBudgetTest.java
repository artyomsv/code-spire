package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LLM budget must stay clear of the Kafka ack threshold.
 *
 * <p>The pairing has no single owner: one value is a {@code spire.*} property, the others belong to
 * a SmallRye channel, and nothing but this check relates them. When the first two were equal (both
 * 60s), a review that used its full budget went unacknowledged, failed the {@code commands-in}
 * channel, and stalled every later command — recoverable only by seeking the consumer group past
 * the record.
 */
class LlmTimeoutBudgetTest {

    /** One record in flight — the channel pins this, so it is the shipped shape. */
    private static final int SERIAL = 1;

    @Test
    void aThresholdWithHeadroomForEveryCallIsAccepted() {
        assertNull(LlmTimeoutBudget.refusalFor(180, 900_000, SERIAL));
    }

    /**
     * Guards the pairing as CONFIGURED, not merely as computed.
     *
     * <p>Every input comes out of {@code application.yml}, including the LLM budget. An earlier
     * version hardcoded the 180 and read only the ack side, so raising the shipped
     * {@code SPIRE_LLM_TIMEOUT_SECONDS} default to 600 would have made the worker refuse to boot
     * while this test stayed green — a guard that passes the regression it exists to catch.
     */
    @Test
    void theShippedDefaultsAreSafe() {
        Map<String, Object> yaml = applicationYaml();
        int timeout = (int) shippedDefault(yaml, "spire.llm.timeout-seconds");
        int ack = (int) shippedDefault(yaml,
                "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms");
        int inFlight = (int) (shippedDefault(yaml, "mp.messaging.incoming.commands-in.max.poll.records")
                * shippedDefault(yaml, "mp.messaging.incoming.commands-in.max-queue-size-factor"));

        assertNull(LlmTimeoutBudget.refusalFor(timeout, ack, inFlight),
                "the shipped application.yml values must clear each other");
        assertTrue(ack <= LlmTimeoutBudget.needed(timeout, inFlight) * LlmTimeoutBudget.LOOSE_THRESHOLD_FACTOR,
                "the shipped threshold must not be so loose that it stops detecting a stall");
    }

    @Test
    void aThresholdEqualToTheBudgetIsRefused() {
        // The boundary case, and the one that actually shipped: 60s of LLM budget against SmallRye's
        // 60000ms default. Equal is not enough — the record is acked AFTER the call returns.
        assertNotNull(LlmTimeoutBudget.refusalFor(60, 60_000, SERIAL));
    }

    @Test
    void aThresholdEqualToTheWholeCommandBudgetIsStillRefused() {
        long exactly = LlmTimeoutBudget.needed(180, SERIAL);
        assertNotNull(LlmTimeoutBudget.refusalFor(180, (int) exactly, SERIAL),
                "the threshold must EXCEED the command budget, not merely match it");
        assertNull(LlmTimeoutBudget.refusalFor(180, (int) exactly + 1, SERIAL));
    }

    @Test
    void aReconcileAndReviewPairIsBudgetedFor() {
        // ADR-019 runs two paid calls inside one GenerateReview. A threshold sized for one of them
        // looks generous and still stalls, which is why the multiplier exists.
        assertNotNull(LlmTimeoutBudget.refusalFor(180, 200_000, SERIAL),
                "room for one call is not room for the reconcile+review pair");
    }

    /**
     * The posting path may SLEEP up to spire.scm.rate-limit-budget-seconds (180s) inside a single
     * PostComments while backing off a rate-limited SCM. An overhead allowance smaller than that
     * would call a pairing safe while one throttled posting run outran it unaided — which is exactly
     * how the first version of this check was wrong, at 120s.
     */
    @Test
    void theOverheadAllowanceCoversThePostingPathsOwnSleepBudget() {
        assertTrue(LlmTimeoutBudget.NON_LLM_OVERHEAD_MS > LlmTimeoutBudget.POSTING_BUDGET_FLOOR_MS,
                "the allowance must exceed the sleep the posting path is already permitted");
    }

    @Test
    void everythingThatIsNotAModelCallIsBudgetedFor() {
        // Diff and PR fetches, a thread fetch per prior finding, the retry ladder over all of them,
        // the context blob read and posting. Leaving them out called a pairing safe while the
        // record's real lifetime exceeded it.
        long callsOnly = 180L * 1000L * LlmTimeoutBudget.LLM_CALLS_PER_COMMAND;
        assertNotNull(LlmTimeoutBudget.refusalFor(180, (int) callsOnly + 1, SERIAL),
                "a threshold that covers only the model calls is not enough");
    }

    /**
     * The clock starts when a record is POLLED, so every record queued ahead of one counts against
     * its age. A threshold sized for a single record is not sized for a prefetched burst — the exact
     * shape that ages a record out however generous the number looks.
     */
    @Test
    void recordsQueuedAheadCountAgainstTheBudget() {
        int fineForOne = (int) LlmTimeoutBudget.needed(180, 1) + 1;
        assertNull(LlmTimeoutBudget.refusalFor(180, fineForOne, 1));
        assertNotNull(LlmTimeoutBudget.refusalFor(180, fineForOne, 2),
                "two records in flight need twice the room");
    }

    /**
     * SmallRye documents 0 as disabling this monitoring outright. Refused with its own message: the
     * headroom text would tell an operator to raise a number they had deliberately zeroed, and the
     * generic refusal would not say that stall detection is off.
     */
    @Test
    void aThresholdOfZeroIsRefusedAsDisablingMonitoring() {
        String refusal = LlmTimeoutBudget.refusalFor(180, 0, SERIAL);
        assertNotNull(refusal);
        assertTrue(refusal.contains("disables stall monitoring"), refusal);
    }

    @Test
    void theRefusalNamesBothSettingsAndTheNumberToBeat() {
        String refusal = LlmTimeoutBudget.refusalFor(60, 60_000, SERIAL);
        assertNotNull(refusal);
        assertTrue(refusal.contains("SPIRE_KAFKA_ACK_MAX_AGE_MS"), "names the variable to change");
        assertTrue(refusal.contains("spire.llm.timeout-seconds"), "names the other half of the pairing");
        assertTrue(refusal.contains(String.valueOf(LlmTimeoutBudget.needed(60, SERIAL))),
                "names the value the threshold must beat");
    }

    /**
     * Returned, not thrown. One error channel means the startup observer has one thing to handle and
     * every refusal clears the same actionability bar as the others.
     */
    @Test
    void aNonPositiveBudgetIsRefusedRatherThanThrown() {
        assertNotNull(LlmTimeoutBudget.refusalFor(0, 900_000, SERIAL));
        String refusal = LlmTimeoutBudget.refusalFor(-1, 900_000, SERIAL);
        assertNotNull(refusal);
        assertTrue(refusal.contains("SPIRE_LLM_TIMEOUT_SECONDS"), refusal);
    }

    /**
     * The multiplier the refusal depends on comes from two channel settings, and reading them wrong
     * would silently under-count exactly the burst this exists to price in.
     */
    @Test
    void theInFlightCountIsThePollSizeTimesTheQueueFactor() {
        LlmTimeoutBudget budget = new LlmTimeoutBudget();
        budget.maxPollRecords = 500;
        budget.maxQueueSizeFactor = 2;
        assertEquals(1000, budget.inFlightRecords(), "the connector defaults, which the channel overrides");

        budget.maxPollRecords = 1;
        budget.maxQueueSizeFactor = 1;
        assertEquals(1, budget.inFlightRecords(), "the shipped, pinned shape");

        // A zero or negative from a hand-edited config must not collapse the budget to nothing.
        budget.maxPollRecords = 0;
        budget.maxQueueSizeFactor = 0;
        assertEquals(1, budget.inFlightRecords(), "never below one record");
    }

    @Test
    void theBudgetIsExposedAsADuration() {
        LlmTimeoutBudget budget = new LlmTimeoutBudget();
        budget.timeoutSeconds = 42;
        assertEquals(42, budget.timeout().toSeconds());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationYaml() {
        try (InputStream in = LlmTimeoutBudgetTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml must be on the test classpath");
            return (Map<String, Object>) new Yaml().load(in);
        } catch (IOException | YAMLException | ClassCastException e) {
            throw new AssertionError("could not read application.yml", e);
        }
    }

    /**
     * The value an operator who sets nothing gets: walks the dotted key and unwraps the
     * {@code ${VAR:default}} form. Names the key it could not reach, so a restructured descriptor
     * reports which setting moved rather than an anonymous null.
     */
    @SuppressWarnings("unchecked")
    private static long shippedDefault(Map<String, Object> yaml, String dottedKey) {
        Object node = yaml;
        StringBuilder walked = new StringBuilder();
        for (String segment : dottedKey.split("\\.")) {
            walked.append(walked.isEmpty() ? "" : ".").append(segment);
            assertTrue(node instanceof Map, "application.yml has no map at '" + walked + "'");
            node = ((Map<String, Object>) node).get(segment);
            assertNotNull(node, "application.yml no longer defines '" + walked + "'");
        }
        String raw = String.valueOf(node).trim();
        int colon = raw.lastIndexOf(':');
        String value = colon < 0 ? raw : raw.substring(colon + 1).replace("}", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new AssertionError("'" + dottedKey + "' is not a number: " + raw, e);
        }
    }
}
