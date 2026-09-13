package dev.codespire.contract.work;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Numeric authority narrows by minimum; protected paths accumulate without glob subtraction. */
public record WorkPolicyLimits(long gateTtlSeconds, long maxRunsPerItem, long maxStepsPerPlan,
                               long maxWallClockSeconds, long maxCostMillicents, long maxCallsPerItem,
                               Set<String> protectedPaths) {
    /** Older profiles authorize no execution until their numeric limits are explicitly configured. */
    public static WorkPolicyLimits inactive() { return new WorkPolicyLimits(86400, 0, 0, 0, 0, 0, Set.of()); }
    public WorkPolicyLimits {
        if (gateTtlSeconds < 1) throw new IllegalArgumentException("Gate lifetime must be positive");
        if (gateTtlSeconds > Integer.MAX_VALUE) throw new IllegalArgumentException("Gate lifetime must fit a positive 32-bit number of seconds");
        if (maxRunsPerItem < 0) throw new IllegalArgumentException("Run limit cannot be negative");
        if (maxStepsPerPlan < 0) throw new IllegalArgumentException("Step limit cannot be negative");
        if (maxWallClockSeconds < 0) throw new IllegalArgumentException("Wall-clock limit cannot be negative");
        if (maxCostMillicents < 0) throw new IllegalArgumentException("Cost limit cannot be negative");
        if (maxCallsPerItem < 0) throw new IllegalArgumentException("Call limit cannot be negative");
        protectedPaths = Set.copyOf(Objects.requireNonNull(protectedPaths));
        if (protectedPaths.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Protected paths cannot be blank");
    }

    /** Includes every eligible profile, the admission bound and the current ceiling. */
    public static WorkPolicyLimits meet(Collection<WorkPolicyLimits> bounds, Set<String> mandatoryPaths) {
        if (bounds.isEmpty()) throw new IllegalArgumentException("At least one policy bound is required");
        Set<String> paths = new HashSet<>(mandatoryPaths);
        long ttl = Long.MAX_VALUE, runs = Long.MAX_VALUE, steps = Long.MAX_VALUE;
        long wallClock = Long.MAX_VALUE, cost = Long.MAX_VALUE, calls = Long.MAX_VALUE;
        for (WorkPolicyLimits bound : bounds) {
            ttl = Math.min(ttl, bound.gateTtlSeconds());
            runs = Math.min(runs, bound.maxRunsPerItem());
            steps = Math.min(steps, bound.maxStepsPerPlan());
            wallClock = Math.min(wallClock, bound.maxWallClockSeconds());
            cost = Math.min(cost, bound.maxCostMillicents());
            calls = Math.min(calls, bound.maxCallsPerItem());
            paths.addAll(bound.protectedPaths());
        }
        return new WorkPolicyLimits(ttl, runs, steps, wallClock, cost, calls, paths);
    }
}
