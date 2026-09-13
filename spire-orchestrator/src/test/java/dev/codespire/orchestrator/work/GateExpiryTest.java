package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
@TestSecurity(user="TEST-expiry-admin",roles="spire-admin")
class GateExpiryTest extends WorkPolicyFixture {
    @Inject WorkGateExpiry expiry;
    @Inject WorkSourceEffects effects;
    @Test void concurrentExpiryAndAnswerUseTheAggregateLock() throws Exception {
        WorkGate gate=open();at(gate.expiresAt());
        try(var blocker=dataSource.getConnection();var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            // The fixture holds the database lock directly; mutating the production lock cannot alter this setup.
            try(var ps=blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){ps.setString(1,itemId);ps.execute();}
            var expiryResult=pool.submit(()->{transitions.expire(gate.id());return true;});
            java.util.concurrent.Future<WorkItemTransitions.Outcome> answerResult;
            try {
                // Expiry must reach the held item lock first. An answer then waits behind its registry locks.
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->expiryResult.isDone()
                        || count("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid() AND ? IS NOT NULL",itemId)>=1);
                assertFalse(expiryResult.isDone(),"Expiry must wait for the held aggregate lock");
                answerResult=pool.submit(()->transitions.answer(gate.id(),gate.version(),"TEST-racing-answer",true,null,"TEST-expiry-admin"));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->expiryResult.isDone() || answerResult.isDone()
                        || count("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid() AND ? IS NOT NULL",itemId)>=2);
                assertFalse(expiryResult.isDone(),"Expiry must wait for the held aggregate lock");
                assertFalse(answerResult.isDone(),"An answer must wait for the same aggregate lock");
            } finally { blocker.commit(); }
            assertDoesNotThrow(()->expiryResult.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(409,assertDoesNotThrow(()->answerResult.get(15,java.util.concurrent.TimeUnit.SECONDS)).status());
        }
        assertEquals(2,store.load(itemId).gate().version());assertEquals("EXPIRED",store.load(itemId).gate().state());
    }
    @Test void expiryInvalidatesAPendingTrackerEffect() throws Exception {
        WorkGate gate=open();java.util.UUID effect=java.util.UUID.randomUUID();
        effects.enqueue(effect,itemId,store.history(itemId).size(),WorkSourceEffects.Kind.COMMENT,"TEST-pending");
        at(gate.expiresAt());transitions.expire(gate.id());assertEquals("refused",effects.status(effect).state());assertEquals("system",store.load(itemId).gate().channel());
    }
    void at(Instant now) { QuarkusMock.installMockForType(new WorkClock(){@Override public Instant now(){return now;}},WorkClock.class); }
    WorkGate open() throws Exception {
        at(Instant.parse("2099-09-13T12:00:00Z"));configureClamp();WorkItemEvent started=startSpecification();transitions.complete(itemId,completed(started));return store.load(itemId).gate();
    }
    @Test void exactDeadlineRefusesALateApproval() throws Exception {
        WorkGate gate=open();at(gate.expiresAt());
        given().contentType("application/json").body(new WorkGateResource.Answer(gate.version(),"TEST-late",true,null))
                .post("/api/approvals/"+gate.id()+"/answer").then().statusCode(409);
        assertEquals("EXPIRED",store.load(itemId).gate().state());assertEquals("gate_expired",store.load(itemId).reason());
        assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=? AND slot_reserved",itemId));
    }
    @Test void aGateBeforeItsDeadlineRemainsOpen() throws Exception {
        WorkGate gate=open();at(gate.expiresAt().minusNanos(1));transitions.expire(gate.id());
        assertEquals("OPEN",store.load(itemId).gate().state());assertTrue(store.load(itemId).progress().reserved());
    }
    @Test void repeatedExpiryDoesNotAppendAnotherRefusal() throws Exception {
        WorkGate gate=open();at(gate.expiresAt());expiry.sweep();long revision=store.history(itemId).size();expiry.sweep();
        transitions.expire(gate.id());
        assertEquals(revision,store.history(itemId).size());assertEquals("EXPIRED",store.load(itemId).gate().state());
    }
    @Test void scheduledExpiryIncludesTheExactDeadline() throws Exception {
        WorkGate gate=open();at(gate.expiresAt());expiry.sweep();assertEquals("EXPIRED",store.load(itemId).gate().state());assertFalse(store.load(itemId).progress().reserved());
    }
    @Test void anExpiredGateRequiresExplicitReadmission() throws Exception {
        WorkGate gate=open();at(gate.expiresAt());transitions.expire(gate.id());
        assertEquals(409,transitions.resume(itemId,store.history(itemId).size(),false).status());
        assertEquals(200,transitions.resume(itemId,store.history(itemId).size(),true).status());assertEquals(2,store.load(itemId).generation());
    }
}
