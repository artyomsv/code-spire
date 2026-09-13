package dev.codespire.contract.work;

import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkItemLifecycleTest {
    WorkItemEvent.Authority authority(){return new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1);}
    final UUID source=UUID.randomUUID(),repository=UUID.randomUUID();
    final WorkIssueLocation issue=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project","TEST-issue"),"TEST-42",URI.create("https://tracker.example.test/TEST-42"));
    WorkPolicy.Profile profile(Map<WorkPolicy.Phase,String> modes){return new WorkPolicy.Profile(UUID.randomUUID(),"TEST-profile",1,0,modes);}
    WorkItemEvent decide(WorkPolicy.Profile profile){return WorkItemLifecycle.reconcile("TEST-item",source,repository,issue,1,authority(),
            new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile),null);}
    @Test void offIntakeCannotAdvance(){WorkItemEvent item=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"off",WorkPolicy.Phase.SPEC,"auto")));assertEquals("not_eligible",item.workflowStatus());assertEquals("intake_off",item.reason());assertEquals("intake",item.phase());}
    @Test void approveIntakeCannotAdvanceWithoutAnApprovalCapability(){WorkItemEvent item=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"approve",WorkPolicy.Phase.SPEC,"auto")));assertEquals("capability_unavailable",item.workflowStatus());assertEquals("intake_approval_unavailable",item.reason());}
    @Test void offSpecificationStopsTheItem(){WorkItemEvent item=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"off")));assertEquals("stopped",item.workflowStatus());assertEquals("spec_off",item.reason());}
    @Test void omittedSpecificationStopsTheItem(){assertEquals("stopped",decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"auto"))).workflowStatus());}
    @Test void approveSpecificationCannotPretendToHaveAGate(){WorkItemEvent item=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"approve")));assertEquals("capability_unavailable",item.workflowStatus());assertEquals("spec_approval_unavailable",item.reason());}
    @Test void autoSpecificationWaitsForRealInput(){WorkItemEvent item=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto")));assertEquals("awaiting_input",item.workflowStatus());assertEquals("specification_required",item.reason());assertEquals("spec",item.phase());assertEquals(1,item.generation());}
    @Test void anAbsentSelectionCannotUseAModeVector(){WorkItemEvent item=WorkItemLifecycle.reconcile("TEST-item",source,repository,issue,1,authority(),new WorkPolicy.Selection(null,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto"),List.of(),"no_eligible_label",List.of(),null),null);assertEquals("not_eligible",item.workflowStatus());}
    @Test void laterSelectionsCannotReplaceTheAdmissionPin(){WorkItemEvent previous=decide(profile(Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto")));WorkPolicy.Profile edited=profile(Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto"));WorkItemEvent next=WorkItemLifecycle.reconcile("TEST-item",source,repository,issue,2,authority(),new WorkPolicy.Selection(edited,edited.modes(),List.of(),"policy_selected",List.of(),edited),previous);assertEquals(previous.admittedProfile(),next.admittedProfile());assertEquals(previous.generation(),next.generation());}
}
