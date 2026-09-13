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


abstract class WorkPolicyFixture extends WorkFixture {
    @Inject WorkItemTransitions transitions;

    @BeforeEach void explicitTestExecutionBoundary() { QuarkusMock.installMockForType(new WorkPhaseCapability() {
        @Override public boolean available(WorkItemEvent item,String phase) { return true; }
    },WorkPhaseCapability.class); }
    WorkPolicy.Profile requested, ceiling;
    WorkPolicy.Profile profile(String name, int precedence, Map<WorkPolicy.Phase,String> modes) {
        UUID id=UUID.randomUUID();extraProfiles.add(id);
        return policies.createVersion(new WorkPolicy.Profile(id,"TEST-"+name+"-"+id,1,precedence,modes,
                new WorkPolicyLimits(3600,5,20,7200,2_000_000,40,Set.of("TEST-sensitive/**"))));
    }
    EnumMap<WorkPolicy.Phase,String> automatic() {
        var modes=new EnumMap<WorkPolicy.Phase,String>(WorkPolicy.Phase.class);
        for(var phase:WorkPolicy.Phase.values())modes.put(phase,switch(phase){case DELIVER->"pr";case LAND->"auto_if_green";default->"auto";});
        return modes;
    }
    void configureClamp() {
        requested=profile("autonomous",1_000_000_102,automatic());
        var modes=automatic();modes.put(WorkPolicy.Phase.PLAN,"approve");modes.put(WorkPolicy.Phase.DELIVER,"draft_pr");modes.put(WorkPolicy.Phase.LAND,"approve");
        ceiling=profile("assisted",1_000_000_101,modes);
        policies.save(repository,new WorkPolicyRegistry.Input(1,new WorkPolicyRegistry.Pin(ceiling.id(),1),Map.of(LABEL,new WorkPolicyRegistry.Pin(requested.id(),1))));
    }
    long clampEvents() {
        // history() reloads and decrypts the committed PostgreSQL stream on every call.
        return store.history(itemId).stream().map(e->(WorkItemEvent)e.payload()).filter(e->e.milestone().equals("POLICY_CLAMPED")).count();
    }
    boolean hasClampAttention() {
        List<Map<String,Object>> rows=given().get("/api/attention").then().statusCode(200).extract().jsonPath().getList("$");
        return rows.stream().anyMatch(row->"WORK_POLICY_CLAMPED".equals(row.get("code")) && ("/work-items/"+itemId).equals(row.get("action")));
    }
    void configureHigh() {
        requested=profile("autonomous",1_000_000_102,automatic());ceiling=requested;
        policies.save(repository,new WorkPolicyRegistry.Input(1,new WorkPolicyRegistry.Pin(ceiling.id(),1),Map.of(LABEL,new WorkPolicyRegistry.Pin(requested.id(),1))));
    }
    WorkItemEvent startSpecification() throws Exception {
        intake.accept(signed("900123"));
        assertEquals(200,transitions.resume(itemId,store.history(itemId).size(),false).status());
        WorkItemEvent running=store.load(itemId);
        assertEquals("active",running.workflowStatus());assertEquals("spec",running.phase());
        assertEquals(1,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND phase='spec' AND state='started'",itemId));
        return running;
    }
    WorkItemTransitions.PhaseResult completed(WorkItemEvent item) {
        return new WorkItemTransitions.PhaseResult(item.progress().attemptId(),true,1,1,1);
    }
    void lower(WorkPolicy.Profile value) {
        policies.save(repository,new WorkPolicyRegistry.Input(policies.get(repository).revision(),new WorkPolicyRegistry.Pin(value.id(),1),Map.of(LABEL,new WorkPolicyRegistry.Pin(requested.id(),1))));
    }
}
