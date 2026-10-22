package dev.codespire.orchestrator.work;

import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
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
    @Test void aHandedItemCannotBeReadmitted() throws Exception {
        String id=admit("autonomous",82);WorkItemEvent land=atLand(id);append(id,land);
        append(id,WorkItemLifecycle.landHanded(land,"land_blocked"));
        assertEquals("awaiting_input",store.load(id).workflowStatus());
        readmitRefused(id,"readmission_land_pending");
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
