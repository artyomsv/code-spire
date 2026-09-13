package dev.codespire.contract.work;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WorkPolicyLimitsTest {
    WorkPolicyLimits limits(long ttl, long runs, long steps, long seconds, long cost, long calls) {
        return new WorkPolicyLimits(ttl, runs, steps, seconds, cost, calls, Set.of());
    }
    WorkPolicyLimits broad() { return limits(3600, 10, 20, 7200, 2_000_000, 100); }
    WorkPolicyLimits meet(WorkPolicyLimits narrow) { return WorkPolicyLimits.meet(List.of(broad(), narrow), Set.of()); }

    @Test void zeroGateLifetimeIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 1, 1, 1)); }
    @Test void negativeRunLimitIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(1, -1, 1, 1, 1, 1)); }
    @Test void negativeStepLimitIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(1, 1, -1, 1, 1, 1)); }
    @Test void negativeWallClockIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, -1, 1, 1)); }
    @Test void negativeCostLimitIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, -1, 1)); }
    @Test void negativeCallLimitIsRejected() { assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, -1)); }
    @Test void blankProtectedPathIsRejected() { assertThrows(IllegalArgumentException.class, () -> new WorkPolicyLimits(1, 1, 1, 1, 1, 1, Set.of(" "))); }
    @Test void absentBoundsCannotGrantUnlimitedAuthority() { assertThrows(IllegalArgumentException.class, () -> WorkPolicyLimits.meet(List.of(), Set.of())); }
    @Test void gateLifetimeTakesTheMinimum() { assertEquals(300, meet(limits(300, 10, 20, 7200, 2_000_000, 100)).gateTtlSeconds()); }
    @Test void runLimitTakesTheMinimum() { assertEquals(2, meet(limits(3600, 2, 20, 7200, 2_000_000, 100)).maxRunsPerItem()); }
    @Test void stepLimitTakesTheMinimum() { assertEquals(3, meet(limits(3600, 10, 3, 7200, 2_000_000, 100)).maxStepsPerPlan()); }
    @Test void wallClockTakesTheMinimum() { assertEquals(60, meet(limits(3600, 10, 20, 60, 2_000_000, 100)).maxWallClockSeconds()); }
    @Test void costLimitTakesTheMinimum() { assertEquals(100, meet(limits(3600, 10, 20, 7200, 100, 100)).maxCostMillicents()); }
    @Test void callLimitTakesTheMinimum() { assertEquals(4, meet(limits(3600, 10, 20, 7200, 2_000_000, 4)).maxCallsPerItem()); }
    @Test void zeroCapsRemainAStopRatherThanBecomingUnlimited() { assertEquals(limits(1, 0, 0, 0, 0, 0), meet(limits(1, 0, 0, 0, 0, 0))); }
    @Test void protectedPathsIncludeEveryBound() {
        var first = new WorkPolicyLimits(1, 1, 1, 1, 1, 1, Set.of("TEST-deploy/**"));
        var second = new WorkPolicyLimits(1, 1, 1, 1, 1, 1, Set.of("TEST-security/**"));
        assertEquals(Set.of("TEST-deploy/**", "TEST-security/**"), WorkPolicyLimits.meet(List.of(first, second), Set.of()).protectedPaths());
    }
    @Test void mandatoryPathsCannotBeRemovedByAnEmptyProfile() {
        assertEquals(Set.of("TEST-mandatory/**"), WorkPolicyLimits.meet(List.of(broad()), Set.of("TEST-mandatory/**")).protectedPaths());
    }
    @Test void pathsCannotChangeAfterThePolicySnapshot() {
        Set<String> input = new HashSet<>(Set.of("TEST-protected/**"));
        var policy = new WorkPolicyLimits(1, 1, 1, 1, 1, 1, input);
        input.clear();
        assertEquals(Set.of("TEST-protected/**"), policy.protectedPaths());
        assertThrows(UnsupportedOperationException.class, () -> policy.protectedPaths().clear());
    }
}
