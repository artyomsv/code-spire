package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Retry land and Stop on an item land handed to the operator (M4 land, spec §7.6, A3). Retry opens a fresh land gate
 * on the head that was verified and reviewed, never on the pull request's current head and never by reusing an
 * earlier approval; Stop ends the item and leaves the pull request alone. The endpoints, the control, the decider, the
 * sweep and the GitHub lander are production; GitHub is the fixture's WireMock.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandRetryIT extends WorkLandFixture {
    static final String FIXED="f".repeat(40);

    /** An approved land GitHub refuses for something only a person can give. */
    String handedBlocked() throws Exception {
        String id=approved();openAt(HEAD,"blocked",null);land.drain();
        handed(id,"land_blocked");return id;
    }
    ValidatableResponse command(String id,String action,long revision,String note) {
        return given().contentType("application/json").body(Map.of("expectedRevision",revision,"note",note))
                .post("/api/work-items/"+id+"/"+action).then();
    }
    ValidatableResponse retry(String id,String note) {return command(id,"retry-land",store.history(id).size(),note);}
    ValidatableResponse stop(String id,String note) {return command(id,"stop-land",store.history(id).size(),note);}
    long effects(String id,String state) throws Exception {
        return count("SELECT count(*) FROM work_land_effect WHERE work_item_id=? AND state=?",id,state);
    }
    WorkGate lastApproved(String id) {
        return store.history(id).stream().map(event->((WorkItemEvent)event.payload()).gate())
                .filter(gate->gate!=null && "land".equals(gate.phase()) && "APPROVED".equals(gate.state())).reduce((first,second)->second).orElseThrow();
    }

    @Test void retryLandNeedsANoteAndTheRevision() throws Exception {
        String id=handedBlocked();long revision=store.history(id).size();
        retry(id," ").statusCode(400);
        command(id,"retry-land",revision-1,"TEST-retry note").statusCode(409).body("reason",is("work_item_changed"));
        assertEquals(revision,store.history(id).size());assertNull(store.load(id).gate());
    }
    @Test void retryLandOpensAFreshGateThenLandsAfterApproval() throws Exception {
        String id=handedBlocked();WorkGate earlier=lastApproved(id);
        retry(id,"TEST-the code owners approved").statusCode(200);
        WorkItemEvent gated=store.load(id);
        assertEquals("land",gated.phase());assertEquals("waiting_approval",gated.workflowStatus());assertEquals("LAND_RETRY_REQUESTED",gated.milestone());
        assertEquals("OPEN",gated.gate().state());assertEquals(HEAD,gated.gate().artifact());assertNotEquals(earlier.id(),gated.gate().id());
        openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        approve(id);land.drain();
        landed(id);assertEquals(1,merges());
        assertEquals(1,effects(id,"handed"));assertEquals(1,effects(id,"done"));
    }
    @Test void retryLandRefusesAMovedPullRequestHead() throws Exception {
        String id=handedBlocked();long revision=store.history(id).size();
        openAt(MOVED,"clean",null);
        retry(id,"TEST-retry note").statusCode(409).body("reason",is("land_head_moved"));
        assertEquals(revision,store.history(id).size(),"no gate opens on a head nobody reviewed");
        assertNull(store.load(id).gate());assertEquals("land_blocked",store.load(id).reason());
    }
    @Test void anEarlierApprovedGateIsNeverReused() throws Exception {
        String id=handedBlocked();WorkGate earlier=lastApproved(id);
        retry(id,"TEST-retry note").statusCode(200);
        openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        WorkItemTransitions.Outcome reused=transitions.answer(earlier.id(),earlier.version(),"TEST-reused-"+UUID.randomUUID(),true,null,"TEST-land-admin");
        assertEquals(409,reused.status());assertEquals("gate_changed",reused.reason());
        land.drain();
        assertEquals(0,merges());assertEquals("waiting_approval",store.load(id).workflowStatus());
    }
    @Test void recheckIsRefusedWhileHanded() throws Exception {
        String id=handedBlocked();long revision=store.history(id).size();
        WorkItemTransitions.Outcome recheck=transitions.resume(id,revision,false);
        assertEquals(409,recheck.status());assertEquals("retry_land_required",recheck.reason());
        given().contentType("application/json").body(new WorkItemResource.Resume(revision,false))
                .post("/api/work-items/"+id+"/resume").then().statusCode(409).body("reason",is("retry_land_required"));
        assertEquals(revision,store.history(id).size());assertNull(store.load(id).gate());
    }
    @Test void retryLandIsAudited() throws Exception {
        String id=handedBlocked();
        retry(id,"TEST-the code owners approved").statusCode(200);
        WorkItemEvent gated=store.load(id);
        assertEquals("TEST-land-admin",gated.control().operator());assertEquals("TEST-the code owners approved",gated.control().note());
        assertEquals(HEAD,gated.control().observedHead());
    }
    @Test void retryOutsideAHandOffIsRefused() throws Exception {
        String id=delivered(61);long revision=store.history(id).size();
        // GitHub holds the verified head, so only the state check refuses.
        openAt(HEAD,"clean",null);
        retry(id,"TEST-retry note").statusCode(409).body("reason",is("retry_land_unavailable"));
        assertEquals(revision,store.history(id).size());
    }
    @Test void theRetryGateBindsTheCurrentPolicyRevision() throws Exception {
        String id=handedBlocked();
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();
        policy.mappings().forEach((key,pin)->mappings.put(key,new WorkPolicyRegistry.Pin(pin.id(),pin.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(policy.ceiling().id(),policy.ceiling().version()),mappings));
        long revised=policies.get(repository).revision();
        assertNotEquals(policy.revision(),revised);
        retry(id,"TEST-retry note").statusCode(200);
        assertEquals(revised,store.load(id).gate().policyRevision());
        openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        approve(id);land.drain();
        landed(id);
    }
    @Test void stopLandEndsAHandedItemWithANote() throws Exception {
        String id=handedBlocked();long revision=store.history(id).size();
        stop(id," ").statusCode(400);
        assertEquals(revision,store.history(id).size());
        stop(id,"TEST-merged by hand later").statusCode(200);
        WorkItemEvent stopped=store.load(id);
        assertEquals("stopped",stopped.workflowStatus());assertEquals("land_stopped_by_operator",stopped.reason());assertEquals("LAND_STOPPED",stopped.milestone());
        assertEquals("TEST-land-admin",stopped.control().operator());assertEquals("TEST-merged by hand later",stopped.control().note());
        assertNotNull(stopped.progress().execution().pullRequest());
        assertEquals(0,merges());assertEquals(0,deletes());
        forge.verify(0,patchRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void stopLandOutsideAHandOffIsRefused() throws Exception {
        String id=delivered(61);long revision=store.history(id).size();
        stop(id,"TEST-stop note").statusCode(409).body("reason",is("retry_land_unavailable"));
        assertEquals(revision,store.history(id).size());
    }
    // A fix stopped before delivery (review of slice 7b) goes back to the approved head, so retry decides on that head.
    @Test void retryLandAfterAStoppedFixLandsTheApprovedHead() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);dispatcher.drain();
        handed(id,"land_fix_stopped");
        retry(id,"TEST-pricing entered").statusCode(200);
        WorkItemEvent gated=store.load(id);
        assertEquals("waiting_approval",gated.workflowStatus());assertEquals(HEAD,gated.gate().artifact(),"the approved head, not the fix's checkpoint");
        approve(id);
        assertEquals("land",store.load(id).phase());
    }
    // A fix that pushed and then stopped before its review left an unreviewed head on the pull request (A3).
    @Test void retryLandAfterAStoppedFixThatPushedIsRefused() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        dispatcher.drain();RunCommand.ExecuteWorkRun fix=heldCommands.getLast();
        saga.on(new RunResult.RunWorkReady(fix.runId(),fix.work(),FIXED,List.of("TEST-file"),Map.of("INPUT",7L),9));
        UUID attempt=verifyPassed(id);delivery.advance(attempt);
        saga.on(new RunResult.RunFinished(fix.runId(),"refs/heads/"+fix.execution().branch(),List.of("TEST-file"),List.of(),Map.of("INPUT",7L),false));
        delivery.advance(attempt);
        assertEquals("review",store.load(id).phase(),store.load(id).reason());
        stopBeforeReview(id);
        WorkItemEvent item=store.load(id);
        assertEquals("land_fix_stopped",item.reason());assertEquals(HEAD,item.progress().execution().head());
        openAt(FIXED,"clean",null);long revision=store.history(id).size();
        retry(id,"TEST-retry note").statusCode(409).body("reason",is("land_head_moved"));
        assertEquals(revision,store.history(id).size());
        stop(id,"TEST-stop note").statusCode(200);
        assertEquals("land_stopped_by_operator",store.load(id).reason());
    }
    /** A limit reached as the fix enters review: the stop goes through decide, which hands the open fix over. */
    void stopBeforeReview(String id) {
        QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c,id);var history=store.history(id);WorkItemEvent item=(WorkItemEvent)history.getLast().payload();
                transitions.decide(c,new WorkItemTransitions.Decision(history,WorkItemLifecycle.state(item,"stopped","policy_cap_reached","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false)),
                        "TEST-stop-before-review:"+UUID.randomUUID()));
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }
    // The land gate says what will happen before anyone approves it (spec §7.10), with no land attempt yet.
    @Test void theLandGatePreviewNamesTheSettings() throws Exception {
        landSettings.save(repository,new dev.codespire.orchestrator.factory.LandSettings.Input(0,"rebase","close",2),"TEST-land-admin");
        String id=atLandGate();
        WorkItemResource.LandView preview=resource.get(id).land();
        assertNotNull(preview,"a land gate with no attempt still previews the land");
        assertEquals("rebase",preview.method());assertEquals("close",preview.ticketRule());assertNull(preview.branchDeleted());
        assertNull(preview.checks());assertFalse(preview.fixInProgress());
    }
    /**
     * The item says who answered or commanded land, by the name they signed in with, what each approval was for and
     * the note each command carried (live land acceptance, issues #62 and #66). The raw subject stays beside the name.
     */
    @Test void theItemNamesLandOperatorsTheirNotesAndTheApprovedHeads() throws Exception {
        executeWith("INSERT INTO operator_seen(oidc_subject,username,display_name) VALUES (?,?,?) ON CONFLICT (oidc_subject) DO UPDATE SET username=EXCLUDED.username,display_name=EXCLUDED.display_name",
                "TEST-land-admin","TEST-land-operator","TEST Land Operator");
        try {
            String id=handedBlocked();
            retry(id,"TEST-the code owners approved").statusCode(200);
            approve(id);land.drain();
            assertEquals("awaiting_input",store.load(id).workflowStatus(),store.load(id).reason());
            stop(id,"TEST-merging it by hand").statusCode(200);
            given().get("/api/work-items/"+id).then().statusCode(200)
                    .body("events.findAll{it.type=='GATE_RESOLVED' && it.phase=='land'}.gateArtifact",everyItem(is(HEAD)))
                    .body("events.findAll{it.type=='GATE_RESOLVED' && it.phase=='land'}.size()",is(2))
                    .body("events.find{it.type=='LAND_RETRY_REQUESTED'}.operator",is("TEST-land-admin"))
                    .body("events.find{it.type=='LAND_RETRY_REQUESTED'}.note",is("TEST-the code owners approved"))
                    .body("events.find{it.type=='LAND_STOPPED'}.operator",is("TEST-land-admin"))
                    .body("events.find{it.type=='LAND_STOPPED'}.note",is("TEST-merging it by hand"))
                    .body("operators.find{it.subject=='TEST-land-admin'}.displayName",is("TEST Land Operator"))
                    .body("operators.find{it.subject=='TEST-land-admin'}.username",is("TEST-land-operator"));
        } finally {
            executeWith("DELETE FROM operator_seen WHERE oidc_subject=?","TEST-land-admin");
        }
    }
}
