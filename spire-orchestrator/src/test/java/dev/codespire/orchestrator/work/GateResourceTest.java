package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@QuarkusTest
@TestSecurity(user="TEST-gate-admin",roles="spire-admin")
class GateResourceTest extends WorkPolicyFixture {
    @Test void admissionOpensTheRequiredApprovalWithoutInventingAnExecutor() throws Exception {
        configureHigh();var modes=automatic();modes.put(WorkPolicy.Phase.SPEC,"approve");
        var gated=profile("approval-at-admission",1_000_000_100,modes);lower(gated);
        intake.accept(signed("900123"));assertNotNull(store.load(itemId).gate());assertEquals("OPEN",store.load(itemId).gate().state());
        assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    @Test void removedCurrentLabelCannotApproveAnOldGate() throws Exception {
        WorkGate gate=open();var changed=ticket();changed.putArray("labels");forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(changed.toString())));
        assertEquals(409,answer(gate,"TEST-removed-label",true));assertEquals("SUPERSEDED",store.load(itemId).gate().state());assertEquals("labels_changed_requires_new_decision",store.load(itemId).reason());
    }
    @Test void aChangedItemRevisionCannotApproveThePreviousDecision() throws Exception {
        WorkGate gate=open();
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(()-> {
            try(Connection c=dataSource.getConnection()) {
                var history=store.history(itemId);store.appendDecision(c,history,store.load(itemId).withMilestone("TEST-UNRELATED-CONTROL-FACT"),"TEST-control-update");
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
        assertEquals(409,answer(gate,"TEST-changed-item",true));assertEquals("SUPERSEDED",store.load(itemId).gate().state());
    }
    @Test void anAlreadyClosedGateCannotBeAnsweredAtItsNewVersion() throws Exception {
        WorkGate gate=open();assertEquals(200,answer(gate,"TEST-reject",false));
        assertEquals(409,answer(store.load(itemId).gate(),"TEST-later-approve",true));assertEquals("REJECTED",store.load(itemId).gate().state());
    }
    @Test void aDifferentOperatorCannotReplayAnotherOperatorsAnswer() throws Exception {
        WorkGate gate=open();assertEquals(200,answer(gate,"TEST-key",true));
        assertEquals(409,transitions.answer(gate.id(),gate.version(),"TEST-key",true,"TEST-encrypted-note","TEST-other-subject").status());
    }
    @Test void aChangedNoteCannotReplayTheSameAnswerKey() throws Exception {
        WorkGate gate=open();assertEquals(200,answer(gate,"TEST-key",true));
        assertEquals(409,transitions.answer(gate.id(),gate.version(),"TEST-key",true,"TEST-other-note","TEST-gate-admin").status());
    }
    WorkGate open() throws Exception {
        configureClamp();WorkItemEvent started=startSpecification();transitions.complete(itemId,completed(started));
        WorkGate gate=store.load(itemId).gate();assertEquals("OPEN",gate.state());return gate;
    }
    int answer(WorkGate gate,String key,boolean approve) {
        return given().contentType("application/json").body(new WorkGateResource.Answer(gate.version(),key,approve,"TEST-encrypted-note"))
                .post("/api/approvals/"+gate.id()+"/answer").statusCode();
    }
    @Test void replayedAnswerIsIdempotent() throws Exception {
        WorkGate gate=open();assertEquals(200,answer(gate,"TEST-once",true));
        long revision=store.history(itemId).size();assertEquals(200,answer(gate,"TEST-once",true));
        assertEquals(revision,store.history(itemId).size());assertEquals(2,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
        assertEquals("TEST-gate-admin",store.load(itemId).gate().resolver());
    }
    @Test void aConflictingAnswerDoesNotReuseTheWinningKey() throws Exception {
        WorkGate gate=open();assertEquals(200,answer(gate,"TEST-key",true));
        assertEquals(409,answer(gate,"TEST-key",false));assertEquals("APPROVED",store.load(itemId).gate().state());
    }
    @Test void changedExpectedVersionCannotResolveAnOpenGate() throws Exception {
        WorkGate gate=open();
        given().contentType("application/json").body(new WorkGateResource.Answer(gate.version()+1,"TEST-key",true,null))
                .post("/api/approvals/"+gate.id()+"/answer").then().statusCode(409);
        assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test @TestSecurity(user="TEST-viewer",roles="spire-viewer")
    void viewerCannotResolveAGate() throws Exception {
        WorkGate gate=open();assertEquals(403,answer(gate,"TEST-forbidden",true));assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void resolvedGateDisappearsFromOpenViewsAndKeepsAnEncryptedNote() throws Exception {
        WorkGate gate=open();
        given().get("/api/approvals").then().statusCode(200).body("gate.id",hasItem(gate.id().toString()));
        assertEquals(200,answer(gate,"TEST-reject",false));
        given().get("/api/approvals").then().statusCode(200).body("gate.id",not(hasItem(gate.id().toString())));
        given().get("/api/approvals?history=true").then().statusCode(200).body("gate.id",hasItem(gate.id().toString()));
        assertEquals("TEST-encrypted-note",transitions.gateFromHistory(itemId,gate.id()).note());
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT note FROM work_item_gate WHERE id=?")) {
            ps.setObject(1,gate.id());try(ResultSet rs=ps.executeQuery()){assertTrue(rs.next());assertFalse(new String(rs.getBytes(1),java.nio.charset.StandardCharsets.UTF_8).contains("TEST-encrypted-note"));}
        }
        assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=? AND slot_reserved",itemId));
    }
    @Test void unavailableTrackerDoesNotRecordAnAnswer() throws Exception {
        WorkGate gate=open();forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));
        assertEquals(503,answer(gate,"TEST-unavailable",true));assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void concurrentAnswersProduceOneResolution() throws Exception {
        WorkGate gate=open();
        // Hold the actual aggregate PostgreSQL lock until both HTTP callers have arrived at a database lock.
        try(Connection blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);WorkItemTransitions.lockItem(blocker,itemId);
            Future<Integer> first=pool.submit(()->answer(gate,"TEST-first",true));
            Future<Integer> second=pool.submit(()->answer(gate,"TEST-second",false));
            try {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertTrue(count("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid() AND ? IS NOT NULL",itemId)>=2));
            } finally { blocker.commit(); }
            List<Integer> statuses=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(200,409),statuses);
        }
        assertEquals(2,store.load(itemId).gate().version());
        assertEquals(1,store.history(itemId).stream().map(event->(WorkItemEvent)event.payload()).filter(event->"GATE_RESOLVED".equals(event.milestone())).count());
    }
}
