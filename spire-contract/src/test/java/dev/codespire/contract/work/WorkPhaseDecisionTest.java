package dev.codespire.contract.work;

import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkPhaseDecisionTest {
    WorkItemEvent item(String mode) {
        var modes=Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto",WorkPolicy.Phase.PLAN,mode);
        var profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-policy",1,1,modes,new WorkPolicyLimits(60,5,20,7200,2_000_000,40,Set.of()));
        var selection=new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile,profile.limits());
        return new WorkItemEvent("TEST-item",UUID.randomUUID(),UUID.randomUUID(),new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project","TEST-issue"),"TEST-42",URI.create("https://tracker.example.test/TEST-42")),
                1,1,profile,profile.modes(),new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1),selection,"plan","awaiting_input","phase_completed");
    }
    WorkItemEvent enter(WorkItemEvent item,boolean approved,boolean available) {return WorkItemLifecycle.enter(item,item,4,approved,Instant.EPOCH,available,UUID.randomUUID());}
    WorkItemEvent observe(WorkItemEvent previous,long revision) {return WorkItemLifecycle.reconcile(previous.workItemId(),previous.sourceId(),previous.repositoryId(),previous.issue(),revision,previous.authority(),previous.policy(),previous);}
    @Test void anUnchangedObservationDoesNotInvalidateAnOpenGate(){var previous=enter(item("approve"),false,true);assertEquals(previous,observe(previous,previous.policyRevision()));}
    @Test void aNewObservationPreservesAnOpenPhaseGate(){var previous=enter(item("approve"),false,true);var next=observe(previous,2);assertEquals("plan",next.phase());assertEquals(previous.gate(),next.gate());assertEquals(2,next.policyRevision());}
    @Test void aNewObservationPreservesAnInFlightPhase(){var previous=enter(item("auto"),false,true);var next=observe(previous,2);assertEquals("plan",next.phase());assertEquals(previous.progress(),next.progress());assertEquals("active",next.workflowStatus());}
    @Test void readmittedWaitingWorkDoesNotResetItsCapabilityDecision(){var previous=enter(item("auto").readmit(),false,false);var next=observe(previous,2);assertEquals("capability_unavailable",next.workflowStatus());assertEquals(previous.generation(),next.generation());}
    @Test void approvalModeOpensADurableGateEvenWithoutAnExecutor(){var next=enter(item("approve"),false,false);assertNotNull(next.gate());assertEquals("OPEN",next.gate().state());assertEquals("waiting_approval",next.workflowStatus());assertTrue(next.progress().reserved());assertNull(next.progress().attemptId());}
    @Test void approvalIsConsumedInsteadOfOpeningAnotherGate(){var next=enter(item("approve"),true,true);assertEquals("active",next.workflowStatus());assertNull(next.gate());}
    @Test void offCannotUseAnApprovalToStart(){var next=enter(item("off"),true,true);assertEquals("not_eligible",next.workflowStatus());assertNull(next.progress().attemptId());}
    @Test void anAbsentSelectionCannotStartDespiteAnAutomaticVector(){var item=item("auto");var none=new WorkPolicy.Selection(null,item.policy().effective(),List.of(),"no_eligible_label",List.of(),item.policy().ceiling(),item.policy().limits());
        var next=enter(item.decision(1,item.authority(),none,item.phase(),item.workflowStatus(),item.reason(),item.milestone(),null,item.progress()),false,true);assertEquals("not_eligible",next.workflowStatus());}
    @Test void capExhaustionCannotOpenAGate(){var item=item("approve");var used=item.progress().finish(0,0,40);var next=enter(item.decision(1,item.authority(),item.policy(),item.phase(),item.workflowStatus(),item.reason(),item.milestone(),null,used),false,true);assertEquals("policy_cap_reached",next.reason());assertNull(next.gate());}
    @Test void gatePinsTheDecisionRevisionAndDeadline(){var next=enter(item("approve"),false,true);assertEquals(5,next.gate().itemRevision());assertEquals(1,next.gate().policyRevision());assertEquals(Instant.EPOCH.plusSeconds(60),next.gate().expiresAt());assertEquals(next.authority(),next.gate().authority());}
    @Test void aClampMilestoneIsIncludedInTheGateBinding(){var item=item("approve");var clamped=new WorkPolicy.Selection(item.policy().selected(),item.policy().effective(),List.of(),"policy_clamped",List.of(),item.policy().ceiling(),item.policy().limits());
        var next=WorkItemLifecycle.enter(item.decision(1,item.authority(),clamped,item.phase(),item.workflowStatus(),item.reason(),item.milestone(),null,item.progress()),item,4,false,Instant.EPOCH,true,UUID.randomUUID());assertEquals(6,next.gate().itemRevision());}
    @Test void unavailableExecutionCannotStartAnAttempt(){var next=enter(item("auto"),false,false);assertEquals("capability_unavailable",next.workflowStatus());assertNull(next.progress().attemptId());assertFalse(next.progress().reserved());}
    @Test void intakeAdvancesToSpecificationWithoutInventingAnAttempt(){var item=item("auto");var next=enter(item.decision(1,item.authority(),item.policy(),"intake",item.workflowStatus(),item.reason(),item.milestone(),null,item.progress()),false,true);assertEquals("spec",next.phase());assertEquals("spec",next.progress().attemptPhase());}
    @Test void automaticModeStartsExactlyOneBoundAttempt(){var next=enter(item("auto"),false,true);assertEquals("active",next.workflowStatus());assertEquals("plan",next.progress().attemptPhase());assertNotNull(next.progress().attemptId());assertTrue(next.progress().reserved());}
    @Test void terminalPhaseDoesNotStartAnotherAttempt(){var item=item("auto");var next=enter(item.decision(1,item.authority(),item.policy(),"complete",item.workflowStatus(),item.reason(),item.milestone(),null,item.progress()),false,true);assertEquals("completed",next.workflowStatus());assertNull(next.progress().attemptId());}
    @Test void readmissionPreservesUsageAndAdvancesGeneration(){var item=item("auto");var used=item.progress().start(UUID.randomUUID(),"build",Instant.EPOCH).finish(3,4,5);var next=item.decision(1,item.authority(),item.policy(),item.phase(),item.workflowStatus(),item.reason(),item.milestone(),null,used).readmit();assertEquals(2,next.generation());assertEquals(1,next.progress().runs());assertEquals(3,next.progress().wallSeconds());assertEquals(4,next.progress().costMillicents());assertEquals(5,next.progress().calls());}
}
