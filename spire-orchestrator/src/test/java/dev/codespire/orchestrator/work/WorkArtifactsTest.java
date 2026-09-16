package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkArtifactsTest {
    final WorkIssueLocation spec=location("TEST-spec"), plan=location("TEST-plan");
    final Map<WorkIssueLocation,WorkSource.Fetch> tickets=new HashMap<>();
    int reads;
    final WorkSourceRegistry.Source source=new WorkSourceRegistry.Source(UUID.randomUUID(),"TEST-source",WorkSourceType.GITHUB,
            "https://tracker.example.test","TEST-project","TEST-scope",UUID.randomUUID(),UUID.randomUUID(),true,true,
            new WorkSourceRegistry.Version(1,1,1),null,null,null,null,null,List.of(new WorkSourceRegistry.Person("TEST-actor","TEST-actor-handle","TEST-actor name")));
    final WorkArtifacts artifacts=new WorkArtifacts();
    WorkArtifactsTest() {
        artifacts.mapper=new ObjectMapper();
        artifacts.sources=new WorkSourceRegistry(){@Override public WorkSource client(Source ignored){return client;}};
    }
    final WorkSource client=new WorkSource() {
        public Set<Capability> capabilities(){return Set.of();}
        public WorkIssueLocation resolve(String key){return spec;}
        public Fetch fetch(WorkIssueLocation location){reads++;return tickets.get(location);}
        public WorkPage<WorkIssueLocation> candidates(String cursor){throw new AssertionError("Unused");}
        public WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue,String cursor){throw new AssertionError("Unused");}
        public String comment(WorkIssueLocation issue,String text,String effect){throw new AssertionError("Read-only");}
        public void transition(WorkIssueLocation issue,String transition,String effect){throw new AssertionError("Read-only");}
    };
    static WorkIssueLocation location(String id){return new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project",id),id,URI.create("https://tracker.example.test/"+id));}
    void ticket(WorkIssueLocation lookup,WorkIssueLocation identity,String body){tickets.put(lookup,new WorkSource.Fetch.Found(new WorkTicket(identity,"TEST-title",body,"TEST-open",Set.of())));}
    String planBody(String version,String digest,String steps){return "{\"schemaVersion\":"+version+",\"specificationSha256\":\""+digest+"\",\"steps\":"+steps+"}";}
    String step(){return "[{\"id\":\"TEST-step\",\"instruction\":\"TEST-instruction\"}]";}
    WorkPreparation prepare(String body,String planBody){
        ticket(spec,spec,body);ticket(plan,plan,planBody);
        return new WorkPreparation(new WorkPreparation.Artifact(spec,WorkPreparation.digest(body)),new WorkPreparation.Artifact(plan,WorkPreparation.digest(planBody)),"main","a".repeat(40),"TEST-harness","TEST-model","TEST-operator");
    }
    WorkPreparation valid(){return prepare("TEST-specification",planBody("1",WorkPreparation.digest("TEST-specification"),step()));}
    void invalidPlan(String version,String steps,String detail){var value=prepare("TEST-specification",planBody(version,WorkPreparation.digest("TEST-specification"),steps));var evidence=artifacts.observe(source,value);assertEquals("single_step_plan_required",evidence.failure());assertEquals(detail,evidence.detail(),"The refusal must name the rule the operator has to fix");}
    @Test void readsTheBoundBodiesWithoutPersistingOrChangingThem(){var value=valid();var evidence=artifacts.observe(source,value);assertNull(evidence.failure());assertEquals("TEST-specification",evidence.specification());assertEquals("TEST-instruction",evidence.instruction());assertEquals(2,reads);}
    @Test void anOverflowingSchemaVersionIsNotVersionOne(){invalidPlan("4294967297",step(),"plan_schema_version");}
    @Test void unsupportedSchemaVersionIsRejected(){invalidPlan("2",step(),"plan_schema_version");}
    @Test void fractionalSchemaVersionIsRejected(){invalidPlan("1.5",step(),"plan_schema_version");}
    @Test void malformedPlanIsInvalidRatherThanAnOutage(){var evidence=artifacts.observe(source,prepare("TEST-specification","TEST-not-json"));assertEquals("single_step_plan_required",evidence.failure());assertEquals("plan_not_json",evidence.detail());}
    @Test void planMustBeAnObject(){var evidence=artifacts.observe(source,prepare("TEST-specification","[]"));assertEquals("single_step_plan_required",evidence.failure());assertEquals("plan_schema_version",evidence.detail());}
    @Test void stepsMustBeAnArray(){invalidPlan("1","{\"id\":\"TEST-step\"}","plan_step_count");}
    @Test void exactlyOneStepIsRequired(){String one=step().substring(1,step().length()-1);invalidPlan("1","["+one+","+one+"]","plan_step_count");}
    @Test void stepIdentityMustBeText(){invalidPlan("1","[{\"id\":7,\"instruction\":\"TEST-instruction\"}]","plan_step_fields");}
    @Test void stepIdentityCannotBeBlank(){invalidPlan("1","[{\"id\":\"  \",\"instruction\":\"TEST-instruction\"}]","plan_step_fields");}
    @Test void instructionMustBeText(){invalidPlan("1","[{\"id\":\"TEST-step\",\"instruction\":7}]","plan_step_fields");}
    @Test void instructionCannotBeBlank(){invalidPlan("1","[{\"id\":\"TEST-step\",\"instruction\":\"  \"}]","plan_step_fields");}
    @Test void planMustBindTheSpecificationDigest(){var value=prepare("TEST-specification",planBody("1","b".repeat(64),step()));var evidence=artifacts.observe(source,value);assertEquals("single_step_plan_required",evidence.failure());assertEquals("plan_specification_mismatch",evidence.detail());}
    @Test void changingSpecificationInvalidatesEvidence(){var value=valid();ticket(spec,spec,"TEST-changed");var evidence=artifacts.observe(source,value);assertEquals("artifacts_changed",evidence.failure());assertEquals("specification_changed",evidence.detail(),"Which ticket moved is what the operator has to re-read");}
    @Test void changingPlanInvalidatesEvidence(){var value=valid();ticket(plan,plan,planBody("1",value.specification().sha256(),step())+" ");var evidence=artifacts.observe(source,value);assertEquals("artifacts_changed",evidence.failure());assertEquals("plan_changed",evidence.detail());}
    @Test void aDifferentReturnedIdentityCannotSupplyEvidence(){valid();ticket(spec,location("TEST-other"),"TEST-specification");assertThrows(WorkArtifacts.ArtifactUnavailable.class,()->artifacts.resolve(source,"TEST-spec"));}
    @Test void aBlankBodyCannotSupplyEvidence(){valid();ticket(spec,spec,"  ");assertThrows(WorkArtifacts.ArtifactUnavailable.class,()->artifacts.resolve(source,"TEST-spec"));}
    @Test void anOversizedBodyCannotSupplyEvidence(){valid();ticket(spec,spec,"x".repeat(48*1024+1));assertThrows(WorkArtifacts.ArtifactUnavailable.class,()->artifacts.resolve(source,"TEST-spec"));}
    @Test void missingArtifactsAreUnavailable(){var value=valid();tickets.put(spec,new WorkSource.Fetch.Deleted());assertEquals("artifacts_unavailable",artifacts.observe(source,value).failure());}
    void wrongScope(WorkIssueRef ref){var value=valid();var wrong=new WorkIssueLocation(ref,"TEST-spec",spec.link());ticket(wrong,wrong,"TEST-specification");var rebound=new WorkPreparation(new WorkPreparation.Artifact(wrong,value.specification().sha256()),value.plan(),value.baseBranch(),value.baseCommit(),value.harness(),value.model(),value.registeredBy());assertEquals("artifacts_unavailable",artifacts.observe(source,rebound).failure());assertEquals(0,reads,"Reject the foreign source before a permissive adapter can fetch it");}
    @Test void aDifferentTrackerTypeIsNotTheSelectedSource(){wrongScope(new WorkIssueRef(WorkSourceType.GITLAB,source.origin(),source.projectId(),"TEST-spec"));}
    @Test void aDifferentOriginIsNotTheSelectedSource(){wrongScope(new WorkIssueRef(source.type(),"https://other.example.test",source.projectId(),"TEST-spec"));}
    @Test void aDifferentProjectIsNotTheSelectedSource(){wrongScope(new WorkIssueRef(source.type(),source.origin(),"TEST-other-project","TEST-spec"));}
}
