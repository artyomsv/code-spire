package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.contract.work.WorkVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Delivery's own check that a build passed (M4). The phase transitions refuse the same evidence first, so this
 * guard is the second line: it is tested on its own, or a mutation of it survives every journey test.
 */
class WorkDeliveryPassedTest {
    final WorkExecution built = new WorkExecution("TEST-run", new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "a".repeat(64)),
            "b".repeat(40), null, null, null);

    WorkVerification result(WorkVerification.Outcome outcome, String reason, int exit) {
        return new WorkVerification(UUID.randomUUID(), built.head(), outcome, reason,
                List.of(new WorkVerification.CheckResult("TEST-check", exit, 1, "")));
    }

    @Test void aPassedResultAuthorizesDelivery() {
        assertTrue(WorkDelivery.passed(built.verified(result(WorkVerification.Outcome.PASSED, null, 0))));
    }

    @Test void aBareAttemptFromHistoryDoesNot() {
        assertFalse(WorkDelivery.passed(built.verified(UUID.randomUUID())), "an attempt id with no outcome verified nothing");
    }

    @Test void aFailedOrUnverifiedResultDoesNot() {
        assertFalse(WorkDelivery.passed(built.verified(result(WorkVerification.Outcome.FAILED, "check_failed", 1))));
        assertFalse(WorkDelivery.passed(built.verified(new WorkVerification(UUID.randomUUID(), built.head(),
                WorkVerification.Outcome.UNVERIFIED, "tool_missing", List.of()))));
    }

    @Test void anUnverifiedBuildDoesNot() {
        assertFalse(WorkDelivery.passed(built));
    }
}
