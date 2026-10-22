package dev.codespire.contract.work;

import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The decide table for the facts land records (M4 land, spec §7.3, §7.6, §7.8, §7.9). */
class WorkLandDecisionTest {
    static final PullRequestRef PR=new PullRequestRef(42,"https://forge.example.test/TEST/42",false);
    WorkLanding landing(long number){return new WorkLanding(new PullRequestRef(number,"https://forge.example.test/TEST/"+number,false),"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH);}
    WorkItemEvent atLand(String land,long generation) {
        var modes=new EnumMap<WorkPolicy.Phase,String>(WorkPolicy.Phase.class);
        for(WorkPolicy.Phase phase:WorkPolicy.Phase.values())modes.put(phase,"auto");
        modes.put(WorkPolicy.Phase.DELIVER,"pr");modes.put(WorkPolicy.Phase.LAND,land);
        var profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-policy",1,1,modes,new WorkPolicyLimits(60,5,20,7200,2_000_000,40,Set.of()));
        var selection=new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile,profile.limits());
        var item=new WorkItemEvent("TEST-item",UUID.randomUUID(),UUID.randomUUID(),new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project","TEST-issue"),"TEST-42",URI.create("https://tracker.example.test/TEST-42")),
                generation,1,profile,profile.modes(),new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1),selection,"land","active","phase_started");
        var execution=new WorkExecution("TEST-run",new WorkRunBinding("TEST-item",generation,UUID.randomUUID(),"a".repeat(64)),"b".repeat(40),null,null,PR,"TEST-review");
        var progress=WorkProgress.empty().start(UUID.randomUUID(),"land",Instant.EPOCH).withExecution(execution);
        return item.decision(1,item.authority(),selection,"land","active","phase_started","PHASE_STARTED",null,progress);
    }
    @Test void landedCompletesTheItem(){
        var item=atLand("approve",1);var next=WorkItemLifecycle.landed(item,landing(42));
        assertEquals("complete",next.phase());assertEquals("completed",next.workflowStatus());assertEquals("landed",next.reason());
        assertEquals("WORK_ITEM_LANDED",next.milestone());assertFalse(next.progress().reserved());
        assertEquals("completed",next.progress().attemptState());assertEquals(item.progress().attemptId(),next.progress().attemptId());
        assertEquals(landing(42),next.progress().execution().landing());assertEquals(item.generation(),next.generation());
    }
    @Test void aLandingOfAnotherPullRequestCannotCompleteTheItem(){
        assertThrows(IllegalArgumentException.class,()->WorkItemLifecycle.landed(atLand("approve",1),landing(43)));
    }
    @Test void landHandedWaitsAtLandForTheOperator(){
        var waiting=atLand("approve",1);
        var gated=WorkItemLifecycle.enter(waiting.decision(1,waiting.authority(),waiting.policy(),"land","awaiting_input","phase_completed","PHASE_COMPLETED",null,waiting.progress().reserve(false)),
                waiting,4,false,Instant.EPOCH,true,UUID.randomUUID());
        assertNotNull(gated.gate());
        var approved=WorkItemLifecycle.state(gated,"active","phase_started","PHASE_STARTED",gated.gate().resolve("APPROVED","TEST-operator","TEST-key",null),gated.progress());
        var next=WorkItemLifecycle.landHanded(approved,"land_blocked");
        assertEquals("land",next.phase());assertEquals("awaiting_input",next.workflowStatus());assertEquals("land_blocked",next.reason());
        assertEquals("LAND_HANDED",next.milestone());assertNull(next.gate(),"an approval resolved before the hand-off is never reused");
        assertFalse(next.progress().reserved());assertEquals(approved.progress().execution(),next.progress().execution());
    }
    @Test void everyHandOffReasonWaitsAtLand(){
        assertEquals(Set.of("land_head_moved","land_checks_timeout","land_checks_not_code","land_blocked","land_pr_draft","land_merge_refused",
                "land_merge_method_not_allowed","land_fix_attempts_exhausted","land_item_limit_reached","land_fix_review_blocked",
                "land_checks_failed","land_conflict","land_branch_behind"),WorkItemLifecycle.LAND_HANDOFF_REASONS);
        for(String reason:WorkItemLifecycle.LAND_HANDOFF_REASONS)assertEquals(reason,WorkItemLifecycle.landHanded(atLand("approve",1),reason).reason());
    }
    @Test void anUnknownHandOffReasonIsRefused(){
        assertThrows(IllegalArgumentException.class,()->WorkItemLifecycle.landHanded(atLand("approve",1),"TEST-x"));
    }
    @Test void aClosedPullRequestStopsTheItem(){
        var item=atLand("approve",1);var next=WorkItemLifecycle.pullRequestClosed(item);
        assertEquals("stopped",next.workflowStatus());assertEquals("pr_closed_by_human",next.reason());assertEquals("PULL_REQUEST_CLOSED",next.milestone());
        assertEquals("land",next.phase());assertFalse(next.progress().reserved());assertNull(next.progress().execution().landing());
    }
    @Test void landedInSeesOnlyThisGenerationAndPullRequest(){
        var item=atLand("approve",1);var landed=WorkItemLifecycle.landed(item,landing(42));
        var history=List.of(item,landed);
        assertTrue(WorkItemLifecycle.landedIn(history,1,42));
        assertFalse(WorkItemLifecycle.landedIn(history,2,42),"a landing of an earlier generation is not this one's");
        assertFalse(WorkItemLifecycle.landedIn(history,1,43),"a landing of another pull request is not this one's");
        assertFalse(WorkItemLifecycle.landedIn(List.of(item),1,42));
        // A later fact that is not the landing itself must not hide it.
        assertTrue(WorkItemLifecycle.landedIn(List.of(item,landed,landed.withMilestone("POLICY_OBSERVED")),1,42));
    }
}
