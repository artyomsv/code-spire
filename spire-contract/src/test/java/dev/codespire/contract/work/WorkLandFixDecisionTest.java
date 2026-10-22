package dev.codespire.contract.work;

import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The decide table of the land fix loop (M4 land, spec §7.5, A9): the rewind, the skipped gates and when a fix is over. */
class WorkLandFixDecisionTest {
    static final PullRequestRef PR=new PullRequestRef(42,"https://forge.example.test/TEST/42",false);

    /** An item delivered and reviewed, at land, with every phase in {@code mode} and land approved. */
    WorkItemEvent atLand(String mode) {
        EnumMap<WorkPolicy.Phase,String> modes=new EnumMap<>(WorkPolicy.Phase.class);
        for(WorkPolicy.Phase phase:WorkPolicy.Phase.values())modes.put(phase,mode);
        modes.put(WorkPolicy.Phase.DELIVER,"pr");modes.put(WorkPolicy.Phase.LAND,"approve");
        WorkPolicy.Profile profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-policy",1,1,modes,new WorkPolicyLimits(60,5,20,7200,2_000_000,40,Set.of()));
        WorkPolicy.Selection selection=new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile,profile.limits());
        WorkItemEvent item=new WorkItemEvent("TEST-item",UUID.randomUUID(),UUID.randomUUID(),new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project","TEST-issue"),"TEST-42",URI.create("https://tracker.example.test/TEST-42")),
                1,1,profile,profile.modes(),new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1),selection,"land","active","phase_started");
        WorkExecution execution=new WorkExecution("TEST-run",new WorkRunBinding("TEST-item",1,UUID.randomUUID(),"a".repeat(64)),"b".repeat(40),null,null,PR,"TEST-review");
        WorkProgress progress=WorkProgress.empty().start(UUID.randomUUID(),"build",Instant.EPOCH).start(UUID.randomUUID(),"land",Instant.EPOCH).withExecution(execution);
        return item.decision(1,item.authority(),selection,"land","active","phase_started","PHASE_STARTED",null,progress);
    }
    /** The same item waiting to enter {@code phase}, as advance hands it to enter after the previous phase completed. */
    WorkItemEvent before(WorkItemEvent item,String phase) {
        return item.decision(item.policyRevision(),item.authority(),item.policy(),phase,"awaiting_input","phase_completed","PHASE_COMPLETED",null,item.progress().reserve(false));
    }
    WorkItemEvent enter(WorkItemEvent item,boolean landFix) {
        return WorkItemLifecycle.enter(item,item,7,false,Instant.EPOCH,true,UUID.randomUUID(),landFix);
    }

    @Test void aLandFixRewindsToBuildKeepingThePullRequest(){
        WorkItemEvent item=atLand("auto");WorkItemEvent next=WorkItemLifecycle.landFixRequested(item,"land_checks_failed");
        assertEquals("build",next.phase());assertEquals("awaiting_input",next.workflowStatus());assertEquals("land_checks_failed",next.reason());
        assertEquals("LAND_FIX_REQUESTED",next.milestone());assertNull(next.gate());assertFalse(next.progress().reserved());
        assertEquals(item.progress().execution(),next.progress().execution(),"the pull request survives the rewind");
        // The fix is found again by the land attempt it came from.
        assertEquals(item.progress().attemptId(),next.progress().attemptId());
    }
    @Test void onlyTheThreeCodeRefusalsRequestAFix(){
        for(String reason:List.of("land_checks_failed","land_conflict","land_branch_behind"))
            assertEquals(reason,WorkItemLifecycle.landFixRequested(atLand("auto"),reason).reason());
        assertThrows(IllegalArgumentException.class,()->WorkItemLifecycle.landFixRequested(atLand("auto"),"land_blocked"));
    }
    @Test void behindIsNotCounted(){
        assertEquals(Set.of("land_checks_failed","land_conflict"),WorkItemLifecycle.LAND_FIX_COUNTED);
    }
    @Test void theLandFixFlagAloneSkipsTheBuildGate(){
        WorkItemEvent build=before(atLand("approve"),"build");
        WorkItemEvent fixing=enter(build,true);
        assertEquals("active",fixing.workflowStatus());assertEquals("phase_started",fixing.reason());assertNull(fixing.gate());
        WorkItemEvent ordinary=enter(build,false);
        assertEquals("waiting_approval",ordinary.workflowStatus());assertEquals("build",ordinary.gate().phase());
    }
    @Test void theLandFixFlagAloneSkipsTheDeliverGate(){
        WorkItemEvent item=atLand("approve");
        // No profile offers deliver: approve today, but enter decides on the effective vector, so the skip is still the decider's.
        EnumMap<WorkPolicy.Phase,String> modes=new EnumMap<>(item.policy().effective());modes.put(WorkPolicy.Phase.DELIVER,"approve");
        WorkPolicy.Profile profile=item.policy().selected();
        WorkPolicy.Selection selection=new WorkPolicy.Selection(profile,modes,List.of(),"policy_selected",List.of(),profile,profile.limits());
        WorkItemEvent deliver=item.decision(1,item.authority(),selection,"deliver","awaiting_input","phase_completed","PHASE_COMPLETED",null,item.progress().reserve(false));
        WorkItemEvent fixing=enter(deliver,true);
        assertEquals("active",fixing.workflowStatus());assertNull(fixing.gate());assertEquals("deliver",fixing.progress().attemptPhase());
        WorkItemEvent ordinary=enter(deliver,false);
        assertEquals("waiting_approval",ordinary.workflowStatus());assertEquals("deliver",ordinary.gate().phase());
    }
    @Test void theLandFixFlagNeverSkipsVerifyOrLand(){
        for(String phase:List.of("verify","land")) {
            WorkItemEvent next=enter(before(atLand("approve"),phase),true);
            assertEquals("waiting_approval",next.workflowStatus(),phase);assertEquals(phase,next.gate().phase());
        }
    }
    @Test void aLandFixBuildStartKeepsTheExecutionAndCountsARun(){
        WorkItemEvent item=atLand("auto");WorkItemEvent build=before(item,"build");
        WorkItemEvent fixing=enter(build,true);
        assertEquals(item.progress().runs()+1,fixing.progress().runs());assertEquals("build",fixing.progress().attemptPhase());
        assertEquals(item.progress().execution(),fixing.progress().execution(),"the kept pull request rides the fix build");
        assertNull(enter(build,false).progress().execution(),"an ordinary build starts from nothing");
    }
    WorkItemEvent gateOpened(WorkItemEvent item,String phase) {
        WorkGate gate=new WorkGate(UUID.randomUUID(),1,"OPEN",phase,item.generation(),5,1,item.authority(),"b".repeat(40),Instant.EPOCH,Instant.EPOCH.plusSeconds(60),null,null,null,null);
        return item.decision(1,item.authority(),item.policy(),phase,"waiting_approval","approval_required","GATE_OPENED",gate,item.progress());
    }
    @Test void inProgressEndsAtTheNextLandGateLandingOrHandOff(){
        WorkItemEvent item=atLand("auto");WorkItemEvent requested=WorkItemLifecycle.landFixRequested(item,"land_checks_failed");
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item)));
        assertTrue(WorkItemLifecycle.landFixInProgress(List.of(item,requested)));
        assertTrue(WorkItemLifecycle.landFixInProgress(List.of(item,requested,gateOpened(requested,"verify"))),"a verify gate is part of the fix");
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item,requested,gateOpened(requested,"land"))));
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item,requested,WorkItemLifecycle.landHanded(item,"land_blocked"))));
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item,requested,WorkItemLifecycle.pullRequestClosed(requested))));
        WorkItemEvent landed=WorkItemLifecycle.landed(requested,new WorkLanding(PR,"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH));
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item,requested,landed)));
        assertFalse(WorkItemLifecycle.landFixInProgress(List.of(item,requested,requested.readmit())));
        // A fix requested again after a new land gate is in progress again.
        assertTrue(WorkItemLifecycle.landFixInProgress(List.of(item,requested,gateOpened(requested,"land"),requested)));
    }
    @Test void landHandedRefusesAFixReasonAfterThisSlice(){
        for(String reason:List.of("land_checks_failed","land_conflict","land_branch_behind"))
            assertThrows(IllegalArgumentException.class,()->WorkItemLifecycle.landHanded(atLand("auto"),reason),reason);
        assertEquals("land_fix_run_failed",WorkItemLifecycle.landHanded(atLand("auto"),"land_fix_run_failed").reason());
    }
}
