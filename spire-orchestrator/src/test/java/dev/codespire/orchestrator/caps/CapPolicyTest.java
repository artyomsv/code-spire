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
}
