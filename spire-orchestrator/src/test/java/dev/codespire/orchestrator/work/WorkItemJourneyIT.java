package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user="TEST-prepared-admin",roles="spire-admin")
class WorkItemJourneyIT extends WorkPreparedFixture {
    @Test void threeProfilesProduceDifferentVisibleJourneys() throws Exception {
        String suggest=admit("suggest",51),assisted=admit("assisted",52),autonomous=admit("autonomous",53);
        for(String id:List.of(suggest,assisted,autonomous))register(id);
        dispatcher.drain();
        assertEquals(0,runCount(suggest));assertEquals(0,runCount(assisted));assertEquals(1,runCount(autonomous));assertEquals(1,dispatched.size());
        given().get("/api/work-items/"+suggest).then().statusCode(200).body("phase",is("build"),"workflowStatus",is("not_eligible"),"reason",is("build_off"),"gate",nullValue(),"builds",hasSize(0));
        given().get("/api/work-items/"+assisted).then().statusCode(200).body("phase",is("plan"),"workflowStatus",is("waiting_approval"),"reason",is("approval_required"),
                "gate.state",is("OPEN"),"gate.phase",is("plan"),"gate.version",is(1),"gate.generation",is(1),"builds",hasSize(0));
        given().get("/api/work-items/"+autonomous).then().statusCode(200).body("phase",is("build"),"workflowStatus",is("active"),"gate",nullValue(),"builds",hasSize(1),"builds[0].state",is("sent"),
                "builds[0].generation",is(1),"builds[0].attemptId",is(store.load(autonomous).progress().attemptId().toString()),"builds[0].runId",is(dispatched.getFirst().runId()));
        assertEquals(0,count("SELECT count(*) FROM work_item_gate WHERE work_item_id=?",autonomous));
        for(String id:List.of(suggest,assisted,autonomous)) {
            var loaded=store.load(id);assertEquals(preparation("TEST-prepared-admin"),loaded.preparation());
            assertTrue(store.history(id).stream().anyMatch(e->((WorkItemEvent)e.payload()).milestone().equals("ARTIFACTS_REGISTERED")));
            assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=? AND pr_number IS NOT NULL",id));
            assertFalse(mapper.writeValueAsString(store.history(id)).contains(specification),"History must persist references, never the tracker artifact text");
            assertFalse(mapper.writeValueAsString(store.history(id)).contains("TEST-implement this one existing task"),"The single-step plan instruction must also remain outside history");
            assertEquals(0,store.history(id).stream().map(e->(WorkItemEvent)e.payload()).filter(e->Set.of("spec","plan").contains(e.phase()) && "PHASE_COMPLETED".equals(e.milestone())).count(),"Manual artifact acceptance must not invent successful executor results");
        }
        WorkGate gate=store.load(assisted).gate();assertEquals(store.load(assisted).preparation().binding(),gate.artifact());
        given().contentType("application/json").body(Map.of("expectedVersion",gate.version(),"idempotencyKey","TEST-plan-answer","approve",true,"note","TEST-reviewed prepared plan"))
                .post("/api/approvals/"+gate.id()+"/answer").then().statusCode(200);
        dispatcher.drain();dispatcher.drain();
        assertEquals(1,runCount(assisted));assertEquals(1,runCount(autonomous));assertEquals(0,runCount(suggest));assertEquals(2,dispatched.size());
        given().get("/api/work-items/"+assisted).then().statusCode(200).body("phase",is("build"),"builds",hasSize(1),"builds[0].state",is("sent"),
                "gate.state",is("APPROVED"),"gate.resolver",is("TEST-prepared-admin"),"gate.channel",is("dashboard"),"gate.note",is("TEST-reviewed prepared plan"));
        assertEquals(1,store.history(assisted).stream().map(e->(WorkItemEvent)e.payload()).filter(e->e.milestone().equals("GATE_RESOLVED") && e.gate().state().equals("APPROVED")).count());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND phase='build'",assisted));
        for(var command:dispatched){assertTrue(command.prompt().contains(specification));assertTrue(command.prompt().contains("TEST-implement this one existing task"));assertTrue(command.protectedPaths().contains("TEST-sensitive/**"));}
    }
    @Test void productionWithoutPublicationHoldDoesNotDispatch() throws Exception {
        publicationSupported=false;String id=admit("autonomous",54);register(id);dispatcher.drain();
        assertEquals("build",store.load(id).phase());assertEquals("capability_unavailable",store.load(id).workflowStatus());
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
        assertEquals(0,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?",id));
    }
}
