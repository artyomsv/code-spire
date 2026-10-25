package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@QuarkusTest
@TestSecurity(user="TEST-attention-admin",roles="spire-admin")
class WorkAttentionTest extends WorkPolicyFixture {
    boolean attention(String code,String subject) {
        List<Map<String,Object>> rows=given().get("/api/attention").then().statusCode(200).extract().jsonPath().getList("$");
        return rows.stream().anyMatch(row->code.equals(row.get("code")) && subject.equals(row.get("subject")));
    }
    WorkGate open() throws Exception {
        configureClamp();WorkItemEvent running=startSpecification();transitions.complete(itemId,completed(running));return store.load(itemId).gate();
    }
    @Test void answeringTheGateRemovesItsCurrentAttentionCondition() throws Exception {
        WorkGate gate=open();assertTrue(attention("WORK_APPROVAL_OPEN",issue.issueKey()));
        assertEquals(200,transitions.answer(gate.id(),gate.version(),"TEST-answer",false,null,"TEST-attention-admin").status());
        assertFalse(attention("WORK_APPROVAL_OPEN",issue.issueKey()));
    }
    @Test void readmissionClearsExpiredGateAttentionButKeepsItsHistory() throws Exception {
        WorkGate gate=open();QuarkusMock.installMockForType(new WorkClock(){@Override public Instant now(){return gate.expiresAt();}},WorkClock.class);
        transitions.expire(gate.id());assertTrue(attention("WORK_APPROVAL_EXPIRED",issue.issueKey()));
        transitions.resume(itemId,store.history(itemId).size(),true);assertFalse(attention("WORK_APPROVAL_EXPIRED",issue.issueKey()));
        assertEquals("EXPIRED",transitions.gateFromHistory(itemId,gate.id()).state());
    }
    void append(WorkItemEvent next) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()){WorkItemTransitions.lockItem(c,itemId);store.appendDecision(c,store.history(itemId),next,"TEST-attention:"+UUID.randomUUID());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }
    // M4 land (spec §7.10): an item land handed over has no open gate, so only this row says a person is needed.
    @Test void aHandedItemNeedsTheOperator() throws Exception {
        configureHigh();intake.accept(signed("900123"));WorkItemEvent item=store.load(itemId);
        WorkExecution execution=new WorkExecution("TEST-land-run",new WorkRunBinding(itemId,item.generation(),UUID.randomUUID(),"a".repeat(64)),"b".repeat(40),null,null,
                new dev.codespire.contract.scm.PullRequestRef(901,"https://forge.example.test/TEST-pull/901",false),"TEST-review");
        WorkItemEvent land=item.decision(item.policyRevision(),item.authority(),item.policy(),"land","active","phase_started","PHASE_STARTED",null,
                item.progress().start(UUID.randomUUID(),"land",Instant.EPOCH).withExecution(execution));
        append(land);assertFalse(attention("WORK_LAND_HANDED",issue.issueKey()));
        WorkItemEvent handed=WorkItemLifecycle.landHanded(land,"land_blocked");
        append(handed);assertTrue(attention("WORK_LAND_HANDED",issue.issueKey()));
        // Waiting at land for a tracker that cannot be read is not a hand-off; recheck answers it.
        append(WorkItemLifecycle.state(handed,"awaiting_input","source_unavailable","AUTHORITY_UNAVAILABLE",null,handed.progress()));
        assertFalse(attention("WORK_LAND_HANDED",issue.issueKey()));
    }
    @Test void trackerRecoveryRemovesItsSourceHealthCondition() throws Exception {
        String name=sources.get(source).orElseThrow().name();
        forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-failed-read");assertTrue(attention("WORK_SOURCE_UNAVAILABLE",name));
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-recovered-read");assertFalse(attention("WORK_SOURCE_UNAVAILABLE",name));
    }
}
