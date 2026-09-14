package dev.codespire.contract.work;

import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkPreparationTest {
    final WorkPreparation.Artifact spec=artifact("TEST-spec","a".repeat(64)),plan=artifact("TEST-plan","b".repeat(64));
    WorkPreparation.Artifact artifact(String id,String sha){return new WorkPreparation.Artifact(new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project",id),id,URI.create("https://tracker.example.test/"+id)),sha);}
    WorkPreparation valid(){return new WorkPreparation(spec,plan,"main","a".repeat(40),"TEST-harness","TEST-model","TEST-human");}
    @Test void bothArtifactsAreRequired(){assertThrows(NullPointerException.class,()->new WorkPreparation(null,plan,"main","a".repeat(40),"TEST-harness","TEST-model","TEST-human"));assertThrows(NullPointerException.class,()->new WorkPreparation(spec,null,"main","a".repeat(40),"TEST-harness","TEST-model","TEST-human"));}
    @Test void fieldBoundariesCannotCollideInAnApprovalBinding(){assertNotEquals(new WorkPreparation(spec,plan,"main","a".repeat(40),"TEST-ab","c","TEST-human").binding(),new WorkPreparation(spec,plan,"main","a".repeat(40),"TEST-a","bc","TEST-human").binding());}
    @Test void artifactDigestMustBeAFullLowercaseSha256(){for(String bad:List.of("","a".repeat(63),"A".repeat(64),"g".repeat(64)))assertThrows(IllegalArgumentException.class,()->artifact("TEST-spec",bad));}
    @Test void artifactIdentityCannotBeMissing(){assertThrows(NullPointerException.class,()->new WorkPreparation.Artifact(new WorkIssueLocation(null,"TEST-key",null),"a".repeat(64)));}
    @Test void commitMustBeAFullHash(){for(String bad:List.of("","a".repeat(39),"g".repeat(40)))assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan,"main",bad,"TEST-harness","TEST-model","TEST-human"));}
    @Test void commitCaseCannotChangeTheBinding(){var value=valid();assertEquals(value.binding(),new WorkPreparation(spec,plan,value.baseBranch(),"A".repeat(40),value.harness(),value.model(),value.registeredBy()).binding());}
    @Test void coordinatesAndOperatorCannotBeBlank(){
        assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan," ","a".repeat(40),"TEST-harness","TEST-model","TEST-human"));
        assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan,"main","a".repeat(40)," ","TEST-model","TEST-human"));
        assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan,"main","a".repeat(40),"TEST-harness"," ","TEST-human"));
        assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan,"main","a".repeat(40),"TEST-harness","TEST-model"," "));
    }
    @Test void eachArtifactAndExecutionCoordinateParticipatesInTheDecisionBinding(){
        var value=valid();Set<String> bindings=new HashSet<>();bindings.add(value.binding());
        bindings.add(new WorkPreparation(artifact("TEST-other",spec.sha256()),plan,value.baseBranch(),value.baseCommit(),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,artifact("TEST-other",plan.sha256()),value.baseBranch(),value.baseCommit(),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(artifact("TEST-spec","c".repeat(64)),plan,value.baseBranch(),value.baseCommit(),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,artifact("TEST-plan","c".repeat(64)),value.baseBranch(),value.baseCommit(),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,plan,"TEST-other",value.baseCommit(),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,plan,value.baseBranch(),"b".repeat(40),value.harness(),value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,plan,value.baseBranch(),value.baseCommit(),"TEST-other",value.model(),value.registeredBy()).binding());
        bindings.add(new WorkPreparation(spec,plan,value.baseBranch(),value.baseCommit(),value.harness(),"TEST-other",value.registeredBy()).binding());assertEquals(9,bindings.size());
    }
    @Test void unknownUsageCannotBecomeKnownThroughProgressWithers(){var value=WorkProgress.empty().unknownUsage();assertTrue(value.reserve(true).usageUnknown());assertTrue(value.start(UUID.randomUUID(),"build",Instant.EPOCH).usageUnknown());assertTrue(value.finish(1,2,3).usageUnknown());}
    @Test void unknownUsageCannotPassAnOtherwiseGenerousBound(){assertFalse(WorkProgress.empty().unknownUsage().within(new WorkPolicyLimits(1,10,10,1000,1000,1000,Set.of()),"build"));}
    @Test void everyEventRebuildPreservesThePreparedReferences(){
        var profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-profile",1,0,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto"));
        var selected=new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile);
        var item=WorkItemLifecycle.reconcile("TEST-item",UUID.randomUUID(),UUID.randomUUID(),spec.location(),1,new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1),selected,null).prepared(valid());
        assertEquals(valid(),item.withMilestone("TEST-milestone").preparation());
        assertEquals(valid(),item.decision(2,item.authority(),selected,"plan","awaiting_input","TEST-reason","TEST-milestone",null,item.progress()).preparation());
        assertEquals(valid(),item.readmit().preparation());
    }
    @Test void oldHistoryWithoutPreparationOrUnknownUsageStillDecodes() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-profile",1,0,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.SPEC,"auto"));
        var selected=new WorkPolicy.Selection(profile,profile.modes(),List.of(),"policy_selected",List.of(),profile);
        var item=WorkItemLifecycle.reconcile("TEST-item",UUID.randomUUID(),UUID.randomUUID(),spec.location(),1,new WorkItemEvent.Authority(UUID.randomUUID(),1,1,1),selected,null);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(item);json.remove("preparation");((com.fasterxml.jackson.databind.node.ObjectNode)json.path("progress")).remove("usageUnknown");
        var restored=mapper.treeToValue(json,WorkItemEvent.class);assertNull(restored.preparation());assertFalse(restored.progress().usageUnknown());assertEquals(item.phase(),restored.phase());
        var prepared=item.prepared(valid());assertEquals(prepared,mapper.readValue(mapper.writeValueAsString(prepared),WorkItemEvent.class));
    }
}
