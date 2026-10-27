package dev.codespire.contract.work;

import java.time.Instant;
import java.util.UUID;

/** Attempt identities and usage survive generation changes. A held slot is released by a durable decision. */
public record WorkProgress(UUID attemptId, String attemptPhase, String attemptState, boolean reserved,
                           Instant startedAt, long runs, long steps, long wallSeconds, long costMillicents, long calls,boolean usageUnknown,
                           WorkExecution execution) {
    public WorkProgress(UUID attemptId,String attemptPhase,String attemptState,boolean reserved,Instant startedAt,
                        long runs,long steps,long wallSeconds,long costMillicents,long calls,boolean usageUnknown) {
        this(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,steps,wallSeconds,costMillicents,calls,usageUnknown,null);
    }
    public WorkProgress(UUID attemptId,String attemptPhase,String attemptState,boolean reserved,Instant startedAt,
                        long runs,long steps,long wallSeconds,long costMillicents,long calls) {
        this(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,steps,wallSeconds,costMillicents,calls,false);
    }
    public static WorkProgress empty() { return new WorkProgress(null,null,null,false,null,0,0,0,0,0); }
    public WorkProgress reserve(boolean value) { return new WorkProgress(attemptId,attemptPhase,attemptState,value,startedAt,runs,steps,wallSeconds,costMillicents,calls,usageUnknown,execution); }
    public WorkProgress unknownUsage() { return new WorkProgress(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,steps,wallSeconds,costMillicents,calls,true,execution); }
    public WorkProgress withExecution(WorkExecution value) { return new WorkProgress(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,steps,wallSeconds,costMillicents,calls,usageUnknown,value); }
    public WorkProgress start(UUID id,String phase,Instant now) {
        boolean build="build".equals(phase);
        return new WorkProgress(id,phase,"started",true,now,runs+(build?1:0),steps,wallSeconds,costMillicents,calls,usageUnknown,build?null:execution);
    }
    /** A land fix's build (M4 land, spec §7.5): a run counted like any build, but it keeps the pull request it fixes. */
    public WorkProgress startKeepingExecution(UUID id,String phase,Instant now) {
        boolean build="build".equals(phase);
        return new WorkProgress(id,phase,"started",true,now,runs+(build?1:0),steps,wallSeconds,costMillicents,calls,usageUnknown,execution);
    }
    /** The first limit {@link #within} finds reached, named as the policy names it; null while every one allows more. */
    public String limitReached(WorkPolicyLimits limits,String phase) {
        boolean build="build".equals(phase);
        if(usageUnknown)return "usage_unknown";
        if(build && runs>=limits.maxRunsPerItem())return "maxRunsPerItem";
        if(build && steps>=limits.maxStepsPerPlan())return "maxStepsPerPlan";
        if(wallSeconds>=limits.maxWallClockSeconds())return "maxWallClockSeconds";
        if(costMillicents>=limits.maxCostMillicents())return "maxCostMillicents";
        if(calls>=limits.maxCallsPerItem())return "maxCallsPerItem";
        return null;
    }
    public WorkProgress finish(long wall,long cost,long callCount) {
        if(wall<0 || cost<0 || callCount<0)throw new IllegalArgumentException("Usage cannot be negative");
        return new WorkProgress(attemptId,attemptPhase,"completed",false,startedAt,runs,steps,
                Math.addExact(wallSeconds,wall),Math.addExact(costMillicents,cost),Math.addExact(calls,callCount),usageUnknown,execution);
    }
    /** Late spend belongs to the item even when its original attempt can no longer advance it. */
    public WorkProgress account(long wall,long cost,long callCount) {
        if(wall<0 || cost<0 || callCount<0)throw new IllegalArgumentException("Usage cannot be negative");
        return new WorkProgress(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,steps,
                Math.addExact(wallSeconds,wall),Math.addExact(costMillicents,cost),Math.addExact(calls,callCount),usageUnknown,execution);
    }
    /** A plan step completed (M4 slice 3, A11): steps count completions, so a retried step counts once. */
    public WorkProgress completeStep() { return withSteps(Math.addExact(steps,1)); }
    /**
     * The most steps a plan may have now (M4 slice 3, A8, A11): the step limit, the cursor's {@link WorkSteps#MAX_STEPS},
     * and the runs left, because every step builds in at least one run and a plan delivers only at its end, so a longer
     * plan would stop partway with its runs spent and nothing delivered. Never negative.
     */
    public long planStepBound(WorkPolicyLimits limits) {
        return Math.max(0,Math.min(Math.min(limits.maxStepsPerPlan(),WorkSteps.MAX_STEPS),limits.maxRunsPerItem()-runs));
    }
    /** Readmission starts the count again (decision 12). */
    public WorkProgress withSteps(long value) { return new WorkProgress(attemptId,attemptPhase,attemptState,reserved,startedAt,runs,value,wallSeconds,costMillicents,calls,usageUnknown,execution); }
    /** A land fix's build is no plan step (A11), so the step limit does not refuse it; every other limit does. */
    public boolean withinKeepingExecution(WorkPolicyLimits limits,String phase) { return withSteps(0).within(limits,phase); }
    /** The first limit a land fix's build would cross, the step limit excepted (A11); null while none is reached. */
    public String limitReachedKeepingExecution(WorkPolicyLimits limits) { return withSteps(0).limitReached(limits,"build"); }
    public boolean within(WorkPolicyLimits limits,String phase) {
        return !usageUnknown && (!"build".equals(phase) || runs<limits.maxRunsPerItem() && steps<limits.maxStepsPerPlan())
                && wallSeconds<limits.maxWallClockSeconds() && costMillicents<limits.maxCostMillicents() && calls<limits.maxCallsPerItem();
    }
}
