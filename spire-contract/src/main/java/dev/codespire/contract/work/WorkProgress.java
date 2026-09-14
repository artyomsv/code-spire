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
        return new WorkProgress(id,phase,"started",true,now,runs+(build?1:0),steps+(build?1:0),wallSeconds,costMillicents,calls,usageUnknown,build?null:execution);
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
    public boolean within(WorkPolicyLimits limits,String phase) {
        return !usageUnknown && (!"build".equals(phase) || runs<limits.maxRunsPerItem() && steps<limits.maxStepsPerPlan())
                && wallSeconds<limits.maxWallClockSeconds() && costMillicents<limits.maxCostMillicents() && calls<limits.maxCallsPerItem();
    }
}
