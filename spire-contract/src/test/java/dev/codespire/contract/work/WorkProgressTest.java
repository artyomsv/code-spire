package dev.codespire.contract.work;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkProgressTest {
    WorkPolicyLimits limits=new WorkPolicyLimits(60,5,20,7200,2_000_000,40,Set.of());
    WorkProgress usage(long runs,long steps,long wall,long cost,long calls) {return new WorkProgress(UUID.randomUUID(),"build","completed",false,Instant.EPOCH,runs,steps,wall,cost,calls);}
    @Test void runCapStopsBuild(){assertFalse(usage(5,0,0,0,0).within(limits,"build"));}
    @Test void stepCapStopsBuild(){assertFalse(usage(0,20,0,0,0).within(limits,"build"));}
    @Test void wallCapStopsTheNextPhase(){assertFalse(usage(0,0,7200,0,0).within(limits,"plan"));}
    @Test void costCapStopsTheNextPhase(){assertFalse(usage(0,0,0,2_000_000,0).within(limits,"plan"));}
    @Test void callCapStopsTheNextPhase(){assertFalse(usage(0,0,0,0,40).within(limits,"plan"));}
    @Test void legacyProfilesCannotStartExecution(){assertFalse(WorkProgress.empty().within(WorkPolicyLimits.inactive(),"build"));assertEquals(0,WorkPolicyLimits.inactive().maxRunsPerItem());}
    // A plan step is counted when it completes, not when its build starts (M4 slice 3, A11): a retried step counts once.
    @Test void startingABuildConsumesItsRunButNoStep(){var progress=usage(1,2,3,4,5).start(UUID.randomUUID(),"build",Instant.EPOCH);assertEquals(2,progress.runs());assertEquals(2,progress.steps());assertTrue(progress.reserved());}
    @Test void stepsCountCompletionsNotStarts(){
        var started=usage(1,2,3,4,5).start(UUID.randomUUID(),"build",Instant.EPOCH);
        assertEquals(2,started.steps(),"a start counts no step");
        assertEquals(3,started.completeStep().steps(),"a completion counts one");
    }
    @Test void draftRunsDoNotCountTowardMaxRuns(){
        var spec=usage(5,0,0,0,0).start(UUID.randomUUID(),"spec",Instant.EPOCH);
        assertEquals(5,spec.runs());assertEquals(5,spec.start(UUID.randomUUID(),"plan",Instant.EPOCH).runs());
    }
    // A land fix after a plan that used every step is one more build, never a plan step (A11, decision 12).
    @Test void aLandFixAfterAPlanIsNotAStep(){
        var delivered=usage(1,20,0,0,0);
        assertFalse(delivered.within(limits,"build"),"a plan step past the limit is refused");
        assertTrue(delivered.withinKeepingExecution(limits,"build"),"a land fix is not checked against the step limit");
        assertNull(delivered.limitReachedKeepingExecution(limits));
        assertFalse(usage(5,0,0,0,0).withinKeepingExecution(limits,"build"),"a land fix still counts toward the run limit");
    }
    @Test void readmissionCanResetTheStepCount(){assertEquals(0,usage(1,2,3,4,5).withSteps(0).steps());}
    @Test void otherPhasesDoNotConsumeBuildAttempts(){var progress=usage(1,2,3,4,5).start(UUID.randomUUID(),"spec",Instant.EPOCH);assertEquals(1,progress.runs());assertEquals(2,progress.steps());}
    @Test void resultUsageAccumulatesWithoutResettingAttempts(){var progress=usage(1,2,3,4,5).finish(10,20,30);assertEquals(13,progress.wallSeconds());assertEquals(24,progress.costMillicents());assertEquals(35,progress.calls());assertEquals(1,progress.runs());assertFalse(progress.reserved());}
    @Test void negativeWallIsRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(-1,0,0));}
    @Test void negativeCostIsRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(0,-1,0));}
    @Test void negativeCallsAreRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(0,0,-1));}
    // A land fix builds on the pull request it fixes (M4 land, spec §7.5): the run is counted and the execution kept.
    @Test void startKeepingExecutionCountsTheRun(){
        WorkExecution execution=new WorkExecution("TEST-run",new WorkRunBinding("TEST-item",1,UUID.randomUUID(),"a".repeat(64)),"b".repeat(40),null,null,null);
        WorkProgress progress=usage(1,2,3,4,5).withExecution(execution).startKeepingExecution(UUID.randomUUID(),"build",Instant.EPOCH);
        assertEquals(2,progress.runs());assertEquals(2,progress.steps(),"a fix is not a plan step");assertTrue(progress.reserved());
        assertEquals("started",progress.attemptState());assertEquals(execution,progress.execution());
    }
    // A specification or plan run counts toward wall clock, cost and calls, never toward runs or steps (A11).
    @Test void draftPhasesDoNotCountRunsOrSteps(){
        for(String phase:List.of("spec","plan")){var progress=usage(1,2,3,4,5).start(UUID.randomUUID(),phase,Instant.EPOCH);assertEquals(1,progress.runs(),phase);assertEquals(2,progress.steps(),phase);}
    }
    @Test void belowAllCapsCanProceed(){assertTrue(usage(4,19,7199,1_999_999,39).within(limits,"build"));}
}
