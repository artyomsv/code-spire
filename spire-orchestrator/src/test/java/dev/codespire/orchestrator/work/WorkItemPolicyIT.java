package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;


@QuarkusTest
@TestSecurity(user="TEST-policy-admin", roles="spire-admin")
class WorkItemPolicyIT extends WorkPolicyFixture {
    @Test @TestSecurity(user="TEST-policy-viewer",roles="spire-viewer")
    void viewerCannotResumeOrReadmit() throws Exception {
        configureHigh();intake.accept(signed("900123"));long revision=store.history(itemId).size();
        for(boolean readmit:List.of(false,true))
            given().contentType("application/json").body(new WorkItemResource.Resume(revision,readmit))
                    .post("/api/work-items/"+itemId+"/resume").then().statusCode(403);
        assertEquals(revision,store.history(itemId).size());
        assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void aPolicyEditDuringObservationCannotConsumeThePriorPhaseResult() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();var observation=transitions.observe(source,issue);
        var modes=automatic();modes.put(WorkPolicy.Phase.PLAN,"off");lower(profile("changed-during-read",1_000_000_101,modes));
        long revision=store.history(itemId).size();assertEquals(503,transitions.advance(itemId,-1,observation,false,completed(running)).status());
        assertEquals(revision,store.history(itemId).size());assertEquals("started",store.load(itemId).progress().attemptState());
    }
    @Test void anActorEditDuringObservationCannotConsumeThePriorPhaseResult() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();var observation=transitions.observe(source,issue);
        administration.removeActor(source,"900123",sources.get(source).orElseThrow().version().source());
        long revision=store.history(itemId).size();assertEquals(503,transitions.advance(itemId,-1,observation,false,completed(running)).status());
        assertEquals(revision,store.history(itemId).size());
    }
    @Test void anUnrelatedAttemptCannotCompleteTheActivePhase() throws Exception {
        configureHigh();startSpecification();long revision=store.history(itemId).size();
        assertEquals(409,transitions.complete(itemId,new WorkItemTransitions.PhaseResult(UUID.randomUUID(),true,1,1,1)).status());assertEquals(revision,store.history(itemId).size());
    }
    @Test void staleResumeRevisionCannotStartAPhase() throws Exception {
        configureHigh();intake.accept(signed("900123"));assertEquals(409,transitions.resume(itemId,store.history(itemId).size()+1,false).status());
        assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void anActivePhaseCannotBeReadmitted() throws Exception {
        configureHigh();startSpecification();assertEquals(409,transitions.resume(itemId,store.history(itemId).size(),true).status());assertEquals(1,store.load(itemId).generation());
    }
    @Test void disabledSourceWaitsWithoutStartingTheNextPhase() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();execute("UPDATE work_source SET enabled=false,revision=revision+1 WHERE id=?",source);
        transitions.complete(itemId,completed(running));assertEquals("source_unavailable",store.load(itemId).reason());assertEquals("awaiting_input",store.load(itemId).workflowStatus());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void workflowFilterIncludesOnlyMatchingItemsAndTotals() throws Exception {
        configureHigh();startSpecification();
        given().get("/api/work-items?status=active").then().statusCode(200).body("items.id",hasItem(itemId)).body("total",greaterThanOrEqualTo(1));
        given().get("/api/work-items?status=TEST-absent-state").then().statusCode(200).body("items",empty()).body("total",is(0));
    }
    @Test void lowestEligibleLabelWins() throws Exception {
        configureHigh();var other=profile("lower-label",1_000_000_100,automatic());
        var current=policies.get(repository);policies.save(repository,new WorkPolicyRegistry.Input(current.revision(),new WorkPolicyRegistry.Pin(requested.id(),1),
                Map.of(LABEL,new WorkPolicyRegistry.Pin(requested.id(),1),"TEST-lower",new WorkPolicyRegistry.Pin(other.id(),1))));
        var ticket=ticket();ticket.withArray("labels").addObject().put("name","TEST-lower");forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket.toString())));
        var events=mapper.createArrayNode();
        for(String label:List.of(LABEL,"TEST-lower")) {
            var event=events.addObject().put("id",events.size()+100).put("event","labeled").put("created_at","2026-09-13T12:00:00Z");
            event.putObject("label").put("name",label);event.putObject("actor").put("id",900123);
        }
        forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(okJson(events.toString())));
        intake.accept(signed("900123"));assertEquals(other.id(),store.load(itemId).policy().selected().id());assertEquals(2,store.load(itemId).policy().applied().size());
    }
    @Test void profileEditCannotWidenAnAdmittedVersion() throws Exception {
        configureClamp();WorkItemEvent running=startSpecification();
        policies.createVersion(new WorkPolicy.Profile(requested.id(),requested.name(),2,requested.precedence(),automatic(),requested.limits()));
        var pin=new WorkPolicyRegistry.Pin(requested.id(),2);policies.save(repository,new WorkPolicyRegistry.Input(policies.get(repository).revision(),pin,Map.of(LABEL,pin)));
        transitions.complete(itemId,completed(running));WorkItemEvent next=store.load(itemId);
        assertEquals(1,next.admittedProfile().version());assertEquals("approve",next.policy().effective().get(WorkPolicy.Phase.PLAN));assertEquals("OPEN",next.gate().state());
    }
    @Test void removedLabelStopsTheNextTransition() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();var changed=ticket();changed.putArray("labels");forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(changed.toString())));
        transitions.complete(itemId,completed(running));assertEquals("not_eligible",store.load(itemId).workflowStatus());assertEquals("no_eligible_label",store.load(itemId).reason());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void removedActorStopsTheNextTransition() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();administration.removeActor(source,"900123",sources.get(source).orElseThrow().version().source());
        transitions.complete(itemId,completed(running));assertEquals("not_eligible",store.load(itemId).workflowStatus());assertEquals("actor_not_allowed",store.load(itemId).policy().ignored().getFirst().reason());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void currentCallCapStopsTheNextTransition() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();UUID capped=UUID.randomUUID();extraProfiles.add(capped);
        var value=policies.createVersion(new WorkPolicy.Profile(capped,"TEST-call-cap-"+capped,1,1_000_000_101,automatic(),new WorkPolicyLimits(3600,5,20,7200,2_000_000,1,Set.of())));
        lower(value);transitions.complete(itemId,completed(running));assertEquals("policy_cap_reached",store.load(itemId).reason());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void actualProtectedPathFloorSurvivesAllProfileVersions() throws Exception {
        configureHigh();intake.accept(signed("900123"));
        assertTrue(store.load(itemId).policy().limits().protectedPaths().containsAll(dev.codespire.workspace.ProtectedPaths.CI_FLOOR));
        assertTrue(store.load(itemId).policy().limits().protectedPaths().contains("TEST-sensitive/**"));
    }
    @Test void missingProductionCapabilityCannotCreateAnAttempt() throws Exception {
        QuarkusMock.installMockForType(new WorkPhaseCapability(),WorkPhaseCapability.class);
        configureHigh();intake.accept(signed("900123"));transitions.resume(itemId,store.history(itemId).size(),false);
        assertEquals("capability_unavailable",store.load(itemId).workflowStatus());assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void aRepeatedResultCannotStartAnotherPhase() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();var result=completed(running);transitions.complete(itemId,result);
        long revision=store.history(itemId).size();assertEquals(200,transitions.complete(itemId,result).status());assertEquals(revision,store.history(itemId).size());
        assertEquals(2,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void failedPhaseDoesNotAdvance() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();transitions.complete(itemId,new WorkItemTransitions.PhaseResult(running.progress().attemptId(),false,1,1,1));
        assertEquals("failed",store.load(itemId).workflowStatus());assertEquals("spec",store.load(itemId).phase());assertFalse(store.load(itemId).progress().reserved());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void loweredCeilingStopsAnInFlightItemAtTheNextPhase() throws Exception {
        configureHigh();WorkItemEvent running=startSpecification();
        var modes=automatic();modes.put(WorkPolicy.Phase.PLAN,"off");
        lower(profile("lowered",1_000_000_101,modes));
        assertTrue(sources.get(source).orElseThrow().enabled());
        assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
        assertEquals(200,transitions.complete(itemId,completed(running)).status());
        assertEquals("not_eligible",store.load(itemId).workflowStatus());
        assertEquals("plan_off",store.load(itemId).reason());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId),"Only the completed prior phase may have an execution effect");
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
        forge.verify(0,postRequestedFor(urlMatching(".*/pulls")));
        given().get("/api/work-items/"+itemId).then().statusCode(200).body("reason",is("plan_off")).body("phase",is("plan"));
    }
    @Test void ceilingChangeBeforeGateAnswerRequiresANewDecision() throws Exception {
        configureClamp();WorkItemEvent running=startSpecification();transitions.complete(itemId,completed(running));
        WorkGate gate=store.load(itemId).gate();assertEquals("OPEN",gate.state());assertEquals("plan",gate.phase());
        var modes=new EnumMap<>(ceiling.modes());
        lower(profile("new-assistance",1_000_000_100,modes));
        // Every mode and limit stays identical; the actor/source remain eligible. Only the decision binding is stale.
        assertEquals("approve",transitions.select(transitions.observe(source,issue),store.load(itemId)).effective().get(WorkPolicy.Phase.PLAN));
        given().contentType("application/json").body(new WorkGateResource.Answer(gate.version(),"TEST-answer",true,"TEST-note"))
                .post("/api/approvals/"+gate.id()+"/answer").then().statusCode(409).body("reason",is("policy_changed_requires_new_decision"));
        assertEquals("SUPERSEDED",store.load(itemId).gate().state());assertFalse(store.load(itemId).progress().reserved());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
        given().get("/api/work-items/"+itemId).then().statusCode(200).body("reason",is("policy_changed_requires_new_decision"));
    }
    @Test void aboveCeilingLabelRecordsAndDisplaysClamp() throws Exception {
        configureClamp();assertEquals(itemId,intake.accept(signed("900123")));
        assertEquals("approve",store.load(itemId).policy().effective().get(WorkPolicy.Phase.PLAN));
        assertEquals("draft_pr",store.load(itemId).policy().effective().get(WorkPolicy.Phase.DELIVER));
        assertEquals(1,clampEvents(),"The clamp must survive a fresh event-store read, independently of the selected vector");
        assertTrue(hasClampAttention(),"A current clamp must be visible through the real attention API");
        given().get("/api/work-items/"+itemId).then().statusCode(200)
                .body("profile.name",is(requested.name())).body("ceiling.name",is(ceiling.name()))
                .body("policyReason",is("policy_clamped")).body("effectiveModes.PLAN",is("approve"))
                .body("events.findAll { it.type == 'POLICY_CLAMPED' }.size()",is(1));
    }
    @Test void unchangedClampDoesNotAddAnotherMilestoneForANewPolicyRevision() throws Exception {
        configureClamp();intake.accept(signed("900123"));
        var current=policies.get(repository);
        policies.save(repository,new WorkPolicyRegistry.Input(current.revision(),new WorkPolicyRegistry.Pin(ceiling.id(),1),Map.of(LABEL,new WorkPolicyRegistry.Pin(requested.id(),1))));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-"+UUID.randomUUID());assertEquals(1,clampEvents());assertTrue(hasClampAttention());
    }
    @Test void aRemovedLabelClearsTheCurrentClampCondition() throws Exception {
        configureClamp();intake.accept(signed("900123"));assertTrue(hasClampAttention());
        var changed=ticket();changed.putArray("labels");forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(changed.toString())));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-"+UUID.randomUUID());assertEquals("no_eligible_label",store.load(itemId).policy().reason());
        assertFalse(hasClampAttention());assertEquals(1,clampEvents(),"Clearing the condition must retain the historical milestone");
    }
}
