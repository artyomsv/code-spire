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
    @Test void startingABuildConsumesItsRunAndStep(){var progress=usage(1,2,3,4,5).start(UUID.randomUUID(),"build",Instant.EPOCH);assertEquals(2,progress.runs());assertEquals(3,progress.steps());assertTrue(progress.reserved());}
    @Test void otherPhasesDoNotConsumeBuildAttempts(){var progress=usage(1,2,3,4,5).start(UUID.randomUUID(),"spec",Instant.EPOCH);assertEquals(1,progress.runs());assertEquals(2,progress.steps());}
    @Test void resultUsageAccumulatesWithoutResettingAttempts(){var progress=usage(1,2,3,4,5).finish(10,20,30);assertEquals(13,progress.wallSeconds());assertEquals(24,progress.costMillicents());assertEquals(35,progress.calls());assertEquals(1,progress.runs());assertFalse(progress.reserved());}
    @Test void negativeWallIsRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(-1,0,0));}
    @Test void negativeCostIsRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(0,-1,0));}
    @Test void negativeCallsAreRejected(){assertThrows(IllegalArgumentException.class,()->WorkProgress.empty().finish(0,0,-1));}
    @Test void belowAllCapsCanProceed(){assertTrue(usage(4,19,7199,1_999_999,39).within(limits,"build"));}
}
