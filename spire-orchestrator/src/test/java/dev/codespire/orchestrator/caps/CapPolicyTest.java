package dev.codespire.orchestrator.caps;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unset must mean unlimited, and unset is the default: a deployment that configures nothing must
 * behave exactly as it does today. Shipping a non-null default would silently change a running
 * deployment's behaviour on upgrade -- the mistake V30 made by leaving legacy models rateless, still
 * the most operator-visible consequence of ADR-023.
 *
 * <p>An unparseable stored value is also treated as unset (fail open): a corrupt setting must not
 * refuse every review, since the operator can see the bad value in Settings, whereas a deployment
 * that silently stops reviewing looks like an outage.
 */
@QuarkusTest
class CapPolicyTest {

    @Inject
    CapPolicy policy;

    @Inject
    AppSettingRepository settings;

    // Blank is how this suite represents "unset" without a delete() on AppSettingRepository -- and
    // CapPolicy must treat it exactly like an absent key, so resetting to it here also doubles as
    // coverage for that equivalence.
    @AfterEach
    void clearEveryLimit() {
        settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "");
        settings.set(CapPolicy.KEY_MAX_DIFF_BYTES, "");
        settings.set(CapPolicy.KEY_SPEND_CAP, "");
        settings.set(CapPolicy.KEY_CALLS, "");
        settings.set(CapPolicy.KEY_WINDOW_MINUTES, "");
    }

    @Test
    void everyLimitIsUnsetByDefault() {
        assertTrue(policy.maxChangedFiles().isEmpty(), "an unset cap must be unlimited");
        assertTrue(policy.maxDiffBytes().isEmpty(), "an unset cap must be unlimited");
        assertTrue(policy.spendCapMillicents().isEmpty());
        assertTrue(policy.callCap().isEmpty());
    }

    @Test
    void aStoredLimitIsRead() {
        settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "500");
        assertEquals(500, policy.maxChangedFiles().orElseThrow());
    }

    @Test
    void everyLimitKeyIsReadThroughItsOwnAccessor() {
        settings.set(CapPolicy.KEY_MAX_DIFF_BYTES, "2000000");
        settings.set(CapPolicy.KEY_SPEND_CAP, "500000");
        settings.set(CapPolicy.KEY_CALLS, "100");

        assertEquals(2_000_000L, policy.maxDiffBytes().orElseThrow());
        assertEquals(500_000L, policy.spendCapMillicents().orElseThrow());
        assertEquals(100, policy.callCap().orElseThrow());
    }

    @Test
    void anUnparseableStoredValueIsTreatedAsUnset() {
        settings.set(CapPolicy.KEY_SPEND_CAP, "not-a-number");
        assertTrue(policy.spendCapMillicents().isEmpty(),
                "a corrupt setting must not refuse every review -- fail open, and the operator can see "
                + "the field is wrong in Settings");
    }

    /**
     * A stored {@code "0"} must read as unset, not as a cap of zero — after which
     * {@code usage.spentMillicents() >= 0} is true for every review, forever, on every gate: one row and
     * the deployment stops reviewing.
     *
     * <p>{@code CapSettingsResource} rejects a zero, so this is unreachable through the application
     * <em>today</em> — which is the entire argument this repository has already had and lost. ADR-023
     * held the conversation path safe by construction because the registry guard forbids an unpriceable
     * model; V30 then created rateless models directly in SQL. A migration or a support UPDATE is the
     * same shape, and the read side is where the value is believed.
     */
    @Test
    void aStoredZeroIsUnsetRatherThanACapOfZero() {
        settings.set(CapPolicy.KEY_SPEND_CAP, "0");
        settings.set(CapPolicy.KEY_CALLS, "0");
        settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "0");
        settings.set(CapPolicy.KEY_MAX_DIFF_BYTES, "0");

        assertTrue(policy.spendCapMillicents().isEmpty(), "a zero spend cap would refuse every review");
        assertTrue(policy.callCap().isEmpty(), "and a zero call cap would too");
        assertTrue(policy.maxChangedFiles().isEmpty(), "a zero file limit would refuse every diff");
        assertTrue(policy.maxDiffBytes().isEmpty(), "and a zero byte limit would too");
    }

    /** Negatives share the reason: every one of these keys is a positive quantity. */
    @Test
    void aStoredNegativeIsUnsetToo() {
        settings.set(CapPolicy.KEY_CALLS, "-1");
        assertTrue(policy.callCap().isEmpty());
    }

    @Test
    void theWindowDefaultsToADayWhenUnset() {
        assertEquals(Duration.ofMinutes(1440), policy.window(),
                "unlike the limits, the window itself must always have an effective value");
    }

    @Test
    void aStoredWindowOverridesTheDefault() {
        settings.set(CapPolicy.KEY_WINDOW_MINUTES, "60");
        assertEquals(Duration.ofMinutes(60), policy.window());
    }

    @Test
    void anUnparseableWindowFallsBackToTheDefault() {
        settings.set(CapPolicy.KEY_WINDOW_MINUTES, "not-a-number");
        assertEquals(Duration.ofMinutes(1440), policy.window());
    }

    /**
     * An out-of-range window is arithmetic, not policy: {@code Instant.now().minus(window)} throws
     * beyond the instant range, and it throws inside the attention sweep (taking the whole panel down
     * rather than one row) and inside both spend gates (dead-lettering the review). The REST layer
     * rejects it, and this is the second defence — without it a value stored before that validation
     * existed leaves the operator unable to clear it through the product, because
     * {@code GET /api/settings/caps} constructs the same Duration and 500s.
     */
    @Test
    void anOutOfRangeWindowFallsBackToTheDefaultRatherThanThrowing() {
        settings.set(CapPolicy.KEY_WINDOW_MINUTES, String.valueOf(Long.MAX_VALUE));
        assertEquals(Duration.ofMinutes(1440), policy.window(),
                "a stored window nothing can subtract from must not take the panel and both gates down");

        settings.set(CapPolicy.KEY_WINDOW_MINUTES, String.valueOf(CapPolicy.MAX_WINDOW_MINUTES));
        assertEquals(Duration.ofMinutes(CapPolicy.MAX_WINDOW_MINUTES), policy.window(),
                "the bound itself is usable — a year is a long window, not an invalid one");
    }
}
