package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestSecurity(user="TEST-prepared-admin",roles="spire-admin")
class WorkArtifactRegistrationTest extends WorkPreparedFixture {
    String waiting() throws Exception {String id=admit("assisted",57);register(id);assertEquals("OPEN",store.load(id).gate().state());return id;}
    @Test void changedSpecificationRequiresANewPlanDecision() throws Exception {
        String id=waiting();var gate=store.load(id).gate();stubArtifact(71,57001,"TEST-revised specification");
        assertTrue(sources.get(source).orElseThrow().enabled());assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
        var result=transitions.answer(gate.id(),gate.version(),"TEST-changed-spec",true,"TEST-note","TEST-human");
        assertEquals(409,result.status());assertEquals("artifacts_changed_requires_new_decision",store.load(id).reason());
        assertEquals("SUPERSEDED",store.load(id).gate().state());assertFalse(store.load(id).progress().reserved());dispatcher.drain();assertEquals(0,runCount(id));
    }
    @Test void changedPlanRequiresANewPlanDecision() throws Exception {
        String id=waiting();var gate=store.load(id).gate();stubArtifact(72,57002,plan+" ");
        var result=transitions.answer(gate.id(),gate.version(),"TEST-changed-plan",true,null,"TEST-human");
        assertEquals(409,result.status());assertEquals("SUPERSEDED",store.load(id).gate().state());assertEquals(0,runCount(id));
    }
    @Test void unavailableArtifactsDoNotClaimAnApproval() throws Exception {
        String id=waiting();var gate=store.load(id).gate();long revision=store.history(id).size();
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues/71")).willReturn(aResponse().withStatus(503)));
        assertEquals(503,transitions.answer(gate.id(),gate.version(),"TEST-outage",true,null,"TEST-human").status());
        assertEquals(revision,store.history(id).size());assertEquals("OPEN",store.load(id).gate().state());assertEquals(0,runCount(id));
    }
    @Test void aPlanWithTwoStepsCannotBecomeAPreparedTask() throws Exception {
        String id=admit("autonomous",57);var root=mapper.readTree(plan);((com.fasterxml.jackson.databind.node.ArrayNode)root.path("steps")).add(root.path("steps").get(0).deepCopy());
        stubArtifact(72,57002,root.toString());var prepared=preparation("TEST-human");long revision=store.history(id).size();
        var result=transitions.prepare(id,revision,prepared);assertEquals(409,result.status());assertEquals("single_step_plan_required",result.reason());
        assertEquals(revision,store.history(id).size());assertNull(store.load(id).preparation());assertEquals(0,runCount(id));
    }
    @Test void thePlanMustNameTheRegisteredSpecificationVersion() throws Exception {
        String id=admit("autonomous",57);var root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(plan);root.put("specificationSha256","b".repeat(64));
        stubArtifact(72,57002,root.toString());var result=transitions.prepare(id,store.history(id).size(),preparation("TEST-human"));
        assertEquals(409,result.status());assertEquals("single_step_plan_required",result.reason());assertNull(store.load(id).preparation());
    }
    @Test void anEmptyPlanInstructionCannotStartABuild() throws Exception {
        String id=admit("autonomous",57);var root=mapper.readTree(plan);((com.fasterxml.jackson.databind.node.ObjectNode)root.path("steps").get(0)).put("instruction","");
        stubArtifact(72,57002,root.toString());var result=transitions.prepare(id,store.history(id).size(),preparation("TEST-human"));
        assertEquals(409,result.status());assertEquals("single_step_plan_required",result.reason());assertNull(store.load(id).preparation());
    }
    @Test void registrationRequiresTheCurrentItemRevision() throws Exception {
        String id=admit("autonomous",57);long revision=store.history(id).size();
        assertEquals(409,transitions.prepare(id,revision-1,preparation("TEST-human")).status());
        assertEquals(revision,store.history(id).size());assertNull(store.load(id).preparation());
    }
    @Test void activeWorkCannotReplaceItsArtifacts() throws Exception {
        String id=admit("autonomous",57);register(id);long revision=store.history(id).size();
        var result=transitions.prepare(id,revision,preparation("TEST-replacement"));
        assertEquals(409,result.status());assertEquals("preparation_unavailable",result.reason());assertEquals(revision,store.history(id).size());
    }
    @Test void anExistingAttemptRequiresReadmissionBeforeArtifactReplacement() throws Exception {
        String id=admit("autonomous",57);register(id);publicationSupported=false;dispatcher.drain();assertEquals("stopped",store.load(id).workflowStatus());
        var result=transitions.prepare(id,store.history(id).size(),preparation("TEST-replacement"));
        assertEquals(409,result.status());assertEquals("explicit_readmission_required",result.reason());assertEquals(1,store.load(id).progress().runs());
    }
    @Test void intakeRedeliveryPreservesAPreparedCursorWithoutAnAttemptOrGate() throws Exception {
        String id=admit("suggest",57);register(id);var before=store.load(id);
        assertNull(before.gate());assertNull(before.progress().attemptId());assertEquals(1,before.generation());assertEquals("build",before.phase());
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();policy.mappings().forEach((key,p)->mappings.put(key,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(policy.ceiling().id(),policy.ceiling().version()),mappings));
        var observed=transitions.observe(before);store.reconcile(observed.source(),observed.policy().revision(),observed.evidence(),"TEST-prepared-redelivery");
        assertEquals("build",store.load(id).phase());assertEquals(before.preparation(),store.load(id).preparation());assertEquals("not_eligible",store.load(id).workflowStatus());
    }
    @Test void theApiRecordsTheVerifiedOperatorRatherThanAClaimedActor() throws Exception {
        String id=admit("assisted",57);var prepared=preparation("TEST-untrusted-body-actor");
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(prepared);input.put("expectedRevision",store.history(id).size());
        given().contentType("application/json").body(input.toString()).post("/api/work-items/"+id+"/preparation").then().statusCode(200);
        given().get("/api/work-items/"+id).then().statusCode(200).body("preparation.registeredBy",is("TEST-prepared-admin"),"gate.artifact",is(prepared.binding()));
    }
    @Test void theReferenceApiReturnsFetchedStableIdentityAndDigest() throws Exception {
        String id=admit("assisted",57);
        given().get("/api/work-items/"+id+"/preparation/reference?key=71").then().statusCode(200)
                .body("artifact.location.ref.issueId",is("57001"),"artifact.location.issueKey",is("71"),"artifact.sha256",is(WorkPreparation.digest(specification)),"title",is("TEST-artifact-71"));
        assertNull(store.load(id).preparation(),"A version lookup does not register or approve the task");
    }
    @Test @TestSecurity(user="TEST-viewer",roles="spire-viewer")
    void aViewerCannotResolveOrRegisterArtifactReferences() throws Exception {
        String id=admit("autonomous",57);long revision=store.history(id).size();
        given().get("/api/work-items/"+id+"/preparation/reference?key=71").then().statusCode(403);
        given().contentType("application/json").body(Map.of("expectedRevision",revision)).post("/api/work-items/"+id+"/preparation").then().statusCode(403);
        assertEquals(revision,store.history(id).size());
    }
    @Test void replacingArtifactsSupersedesThePriorGate() throws Exception {
        String id=waiting();var old=store.load(id).gate();
        var original=preparation("TEST-replacement-operator");var replacement=new WorkPreparation(original.specification(),original.plan(),"main","b".repeat(40),original.harness(),original.model(),original.registeredBy());
        assertEquals(200,transitions.prepare(id,store.history(id).size(),replacement).status());
        var current=store.load(id).gate();assertNotEquals(old.id(),current.id());assertNotEquals(old.artifact(),current.artifact());
        assertEquals("SUPERSEDED",transitions.gateFromHistory(id,old.id()).state());assertEquals("OPEN",current.state());
        assertEquals(409,transitions.answer(old.id(),old.version(),"TEST-old-answer",true,null,"TEST-human").status());
    }
}
