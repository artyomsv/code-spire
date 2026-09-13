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
    @Test void trackerRecoveryRemovesItsSourceHealthCondition() throws Exception {
        String name=sources.get(source).orElseThrow().name();
        forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-failed-read");assertTrue(attention("WORK_SOURCE_UNAVAILABLE",name));
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-recovered-read");assertFalse(attention("WORK_SOURCE_UNAVAILABLE",name));
    }
}
