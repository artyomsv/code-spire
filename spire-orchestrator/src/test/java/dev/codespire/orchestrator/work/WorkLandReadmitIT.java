package dev.codespire.orchestrator.work;

import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A landed item, and one handed to the operator at land, cannot be readmitted (M4 land, spec §7.9). The
 * decisions are the real decider's, appended through the real store; nothing produces them yet.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandReadmitIT extends WorkPreparedFixture {
    static final PullRequestRef PR=new PullRequestRef(901,"https://forge.example.test/TEST-pull/901",false);
    @Inject WorkItemControl control;

    WorkItemEvent atLand(String id) {
        WorkItemEvent item=store.load(id);
        WorkExecution execution=new WorkExecution("TEST-land-run",new WorkRunBinding(id,item.generation(),UUID.randomUUID(),"a".repeat(64)),
                "b".repeat(40),null,null,PR,"TEST-review");
        WorkProgress progress=item.progress().start(UUID.randomUUID(),"land",Instant.EPOCH).withExecution(execution);
        return item.decision(item.policyRevision(),item.authority(),item.policy(),"land","active","phase_started","PHASE_STARTED",null,progress);
    }
    void append(String id,WorkItemEvent next) {
        QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()){WorkItemTransitions.lockItem(c,id);store.appendDecision(c,store.history(id),next,"TEST-land-fixture:"+UUID.randomUUID());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }
    void readmitRefused(String id,String reason) {
        long revision=store.history(id).size();long generation=store.load(id).generation();
        given().contentType("application/json").body(new WorkItemResource.Resume(revision,true))
                .post("/api/work-items/"+id+"/resume").then().statusCode(409).body("reason",is(reason));
        assertEquals(revision,store.history(id).size());assertEquals(generation,store.load(id).generation());
    }

    @Test void aLandedItemCannotBeReadmitted() throws Exception {
        String id=admit("autonomous",81);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.landed(land,new WorkLanding(PR,"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH)));
        assertEquals("completed",store.load(id).workflowStatus());assertEquals("landed",store.load(id).reason());
        readmitRefused(id,"readmission_landed");
    }
    // The ticket stays open after a landing so testing can follow (§7.7); a tester's comment is not a takeover.
    @Test void aTrackerCommentDoesNotSuspendALandedItem() throws Exception {
        String id=admit("autonomous",84);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.landed(land,new WorkLanding(PR,"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH)));
        long revision=store.history(id).size();
        control.suspend(id,"tracker","TEST-land-comment","TEST-tester",null);
        assertEquals(revision,store.history(id).size());
        assertEquals("completed",store.load(id).workflowStatus());assertEquals("landed",store.load(id).reason());
    }
    // An item suspended after its landing (recorded before this guard, or merged by hand while suspended) keeps its refusal.
    @Test void aLandedSuspendedItemCannotBeResumed() throws Exception {
        String id=admit("autonomous",85);WorkItemEvent land=atLand(id);append(id,land);
        WorkItemEvent landed=WorkItemLifecycle.landed(land,new WorkLanding(PR,"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH));
        append(id,landed);
        append(id,WorkItemLifecycle.state(landed,"suspended","human_takeover","HUMAN_TAKEOVER",null,landed.progress().reserve(false)));
        long revision=store.history(id).size();long generation=store.load(id).generation();
        var outcome=transitions.resumeControlled(id,revision,transitions.observe(store.load(id)),"TEST-operator","TEST-note","b".repeat(40));
        assertEquals(409,outcome.status());assertEquals("readmission_landed",outcome.reason());
        assertEquals(revision,store.history(id).size());assertEquals(generation,store.load(id).generation());
    }
    @Test void aHandedItemCannotBeReadmitted() throws Exception {
        String id=admit("autonomous",82);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.landHanded(land,"land_blocked"));
        assertEquals("awaiting_input",store.load(id).workflowStatus());
        readmitRefused(id,"readmission_land_pending");
    }
    // Waiting at land for any other reason still leaves the delivered pull request open; readmit would build a second beside it (A8).
    @Test void anItemWaitingAtLandOnUnknownUsageCannotBeReadmitted() throws Exception {
        String id=admit("autonomous",86);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.state(land,"awaiting_input","run_usage_unknown","WORK_ITEM_REFUSED",null,land.progress().finish(0,0,0)));
        readmitRefused(id,"readmission_land_pending");
    }
    @Test void anItemWaitingAtLandForACapabilityCannotBeReadmitted() throws Exception {
        String id=admit("autonomous",87);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.state(land,"capability_unavailable","land_capability_unavailable","CAPABILITY_UNAVAILABLE",null,land.progress().finish(0,0,0)));
        readmitRefused(id,"readmission_land_pending");
    }
    void stopLand(String id) {
        given().contentType("application/json").body(java.util.Map.of("expectedRevision",store.history(id).size(),"note","TEST-stop note"))
                .post("/api/work-items/"+id+"/stop-land").then().statusCode(200);
        WorkItemEvent stopped=store.load(id);
        assertEquals("stopped",stopped.workflowStatus());assertEquals("land_stopped_by_operator",stopped.reason());
        assertNotNull(stopped.progress().execution().pullRequest());
    }
    // Review of slice 9: land unavailable on this forge or for this repository has no other way on; Stop ends it, and
    // only then may the work be built again.
    @Test void anItemWhoseLandIsUnavailableCanBeStoppedThenReadmitted() throws Exception {
        String id=admit("autonomous",89);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.state(land,"capability_unavailable","land_capability_unavailable","CAPABILITY_UNAVAILABLE",null,land.progress().finish(0,0,0)));
        stopLand(id);
        given().contentType("application/json").body(new WorkItemResource.Resume(store.history(id).size(),true))
                .post("/api/work-items/"+id+"/resume").then().statusCode(200);
        assertEquals(2,store.load(id).generation());
    }
    // Switching land off and rechecking leaves the item not eligible at land with its pull request still open (A8).
    @Test void anItemWhoseLandIsOffCannotBeReadmittedButCanBeStopped() throws Exception {
        String id=admit("autonomous",90);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.state(land,"not_eligible","land_off","WORK_ITEM_REFUSED",null,land.progress().finish(0,0,0)));
        readmitRefused(id,"readmission_land_pending");
        stopLand(id);
    }
    // Stop ends land and leaves the pull request to the operator; re-admitting afterwards builds the work again.
    @Test void anItemStoppedAtLandCanStillBeReadmitted() throws Exception {
        String id=admit("autonomous",88);WorkItemEvent land=atLand(id);append(id,land);
        WorkItemEvent handed=WorkItemLifecycle.landHanded(land,"land_blocked");append(id,handed);
        append(id,WorkItemLifecycle.landStopped(handed));
        long revision=store.history(id).size();
        given().contentType("application/json").body(new WorkItemResource.Resume(revision,true))
                .post("/api/work-items/"+id+"/resume").then().statusCode(200);
        assertEquals(2,store.load(id).generation());
    }
    @Test void anItemStoppedElsewhereCanStillBeReadmitted() throws Exception {
        String id=admit("autonomous",83);WorkItemEvent item=store.load(id);
        append(id,item.decision(item.policyRevision(),item.authority(),item.policy(),"plan","stopped","gate_rejected","GATE_RESOLVED",null,item.progress()));
        long revision=store.history(id).size();
        given().contentType("application/json").body(new WorkItemResource.Resume(revision,true))
                .post("/api/work-items/"+id+"/resume").then().statusCode(200);
        assertEquals(2,store.load(id).generation());
    }
}
