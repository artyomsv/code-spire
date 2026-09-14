package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user="TEST-prepared-admin",roles="spire-admin")
class WorkItemRunBridgeTest extends WorkPreparedFixture {
    @Inject RunResultSaga saga;
    @Inject WorkItemRunBridge bridge;
    @Inject FactoryPullRequests proposals;
    @Inject FactoryRunProjection runs;
    @Inject RunCharges charges;
    String build() throws Exception {
        String id=admit("autonomous",55);register(id);dispatcher.drain();assertEquals(1,runCount(id));return id;
    }
    RunResult.RunFinished priorBuildResult() {
        // Explicit test-only prior-phase driver. No M4 verifier and no real container execution is claimed by this fixture.
        return new RunResult.RunFinished(dispatched.getFirst().runId(),null,List.of("TEST-task.txt"),List.of(),Map.of("INPUT",1L),false);
    }
    @Test void itemRunCannotUseStandaloneAutomaticProposal() throws Exception {
        String id=build();String run=dispatched.getFirst().runId();
        // A result carrying a pushed head is deliberately used to make the standalone proposal path eligible.
        var result=new RunResult.RunFinished(run,"refs/heads/spire/TEST-built",List.of("TEST-task.txt"),List.of(),Map.of("INPUT",1L),false);
        runs.apply(result);assertNotNull(runs.find(run).orElseThrow().pushedRef());
        proposals.propose(result);
        assertNull(runs.find(run).orElseThrow().prError(),"The item guard must stop BEFORE attempting the otherwise eligible standalone proposal");
        assertNull(runs.find(run).orElseThrow().prUrl());
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=? AND pr_number IS NOT NULL",id));
    }
    @Test void duplicateResultAdvancesTheItemOnlyOnce() throws Exception {
        String id=build();var result=priorBuildResult();saga.on(result);
        long revision=store.history(id).size(),calls=store.load(id).progress().calls();
        assertEquals("verify",store.load(id).phase());assertEquals("capability_unavailable",store.load(id).workflowStatus());
        assertEquals("verify_capability_unavailable",store.load(id).reason());assertEquals(1,calls);
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND result_processed",id));
        saga.on(result);bridge.recover();
        assertEquals(revision,store.history(id).size());assertEquals(calls,store.load(id).progress().calls());
        assertEquals(1,count("SELECT count(DISTINCT call_ref) FROM llm_charge WHERE subject_id=?",result.runId()));
        assertEquals(1,runCount(id));assertEquals(1,dispatched.size());
    }
    @Test void durableResultCanRecoverBeforeAndAfterTheAggregateCommit() throws Exception {
        String id=build();var result=priorBuildResult();runs.apply(result);charges.record(result);
        assertTrue(bridge.record(result));assertEquals("build",store.load(id).phase());
        assertEquals(0,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND result_processed",id));
        bridge.recover();assertEquals("verify",store.load(id).phase());long revision=store.history(id).size();
        // Stage the crash window after the aggregate committed but before the inbox acknowledgement committed.
        execute("UPDATE work_run_effect SET result_processed=false WHERE work_item_id=?",id);
        bridge.recover();assertEquals(revision,store.history(id).size());
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND result_processed",id));
    }
    @Test void unknownRunCostBlocksContinuationAndSurvivesReadmission() throws Exception {
        String id=build();var result=priorBuildResult();runs.apply(result);
        // The ledger read really has no measurement; a known unmetered zero would not discriminate this guard.
        assertFalse(runs.find(result.runId()).orElseThrow().cost().isKnown());bridge.accept(result);
        var stopped=store.load(id);assertTrue(stopped.progress().usageUnknown());assertEquals("run_usage_unknown",stopped.reason());assertEquals("awaiting_input",stopped.workflowStatus());
        assertEquals(1,stopped.progress().calls());assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND result_processed",id));
        assertEquals(200,transitions.resume(id,store.history(id).size(),true).status());
        assertTrue(store.load(id).progress().usageUnknown());assertEquals("run_usage_unknown",store.load(id).reason());assertEquals(1,store.load(id).progress().calls());
    }
    @Test void anUnobservedAgentDoesNotCompleteTheBuild() throws Exception {
        String id=build();saga.on(priorBuildResult().withAgentUnobserved(true));
        assertEquals("build",store.load(id).phase());assertEquals("failed",store.load(id).workflowStatus());assertEquals(1,store.load(id).progress().calls());
    }
    @Test void aRefusedBuildDoesNotAdvanceToVerify() throws Exception {
        String id=build();saga.on(new RunResult.RunFinished(dispatched.getFirst().runId(),null,List.of("TEST-sensitive/file"),List.of(new RunResult.BlockedChange("TEST-sensitive/file","MODIFY")),Map.of("INPUT",1L),false));
        assertEquals("build",store.load(id).phase());assertEquals("failed",store.load(id).workflowStatus());assertFalse(store.load(id).progress().reserved());
    }
    @Test void aFailedBuildStillRecordsItsUsage() throws Exception {
        String id=build();saga.on(new RunResult.RunFailed(dispatched.getFirst().runId(),"AGENT_FAILED","TEST-agent failure",false,Map.of("INPUT",1L)));
        assertEquals("failed",store.load(id).workflowStatus());assertEquals("build",store.load(id).phase());assertEquals(1,store.load(id).progress().calls());assertFalse(store.load(id).progress().reserved());
    }
    @Test void aProvenPreAgentFailureCanBeReadmittedWithoutInventingACall() throws Exception {
        String id=build();String run=dispatched.getFirst().runId();saga.on(new RunResult.RunFailed(run,"BAD_COMMAND","TEST-rejected before execution",false,null));
        assertEquals(0,count("SELECT count(*) FROM llm_charge WHERE subject_id=?",run));
        var failed=store.load(id);assertEquals("failed",failed.workflowStatus());assertFalse(failed.progress().usageUnknown());assertEquals(0,failed.progress().calls());assertEquals(0,failed.progress().costMillicents());
        assertEquals(200,transitions.resume(id,store.history(id).size(),true).status());assertEquals("active",store.load(id).workflowStatus());dispatcher.drain();
        assertEquals(2,runCount(id));assertEquals(2,dispatched.size());assertEquals(0,store.load(id).progress().calls());
    }
    @Test void theFirstTerminalInboxResultSurvivesADifferentRedelivery() throws Exception {
        String id=build();var result=priorBuildResult();runs.apply(result);charges.record(result);bridge.record(result);
        bridge.record(new RunResult.RunFailed(result.runId(),"AGENT_FAILED","TEST-later conflicting terminal",false,Map.of("INPUT",1L)));
        bridge.recover();assertEquals("verify",store.load(id).phase());assertEquals(1,store.load(id).progress().calls());
    }
    @Test void aCompletedBuildResultIsAcknowledgedWhileVerificationIsActive() throws Exception {
        QuarkusMock.installMockForType(new WorkPhaseCapability(){@Override public boolean available(WorkItemEvent item,String phase){return Set.of("build","verify").contains(phase);}},WorkPhaseCapability.class);
        String id=build();var result=priorBuildResult();saga.on(result);var verifying=store.load(id);
        assertEquals("verify",verifying.phase());assertEquals("active",verifying.workflowStatus());assertEquals("started",verifying.progress().attemptState());
        execute("UPDATE work_run_effect SET result_processed=false WHERE work_item_id=?",id);long revision=store.history(id).size();bridge.recover();
        assertEquals(revision,store.history(id).size());assertEquals(verifying.progress().attemptId(),store.load(id).progress().attemptId());
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND result_processed",id));
    }
    @Test void measuredRunUsageSurvivesReadmissionWithoutDoubleCharging() throws Exception {
        String id=build();var result=priorBuildResult();execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);
        execute("INSERT INTO llm_model_rate(model_id,token_type,rate_millicents_per_million) VALUES (?,'INPUT',7000000)",modelId);
        runs.apply(result);charges.record(result);execute("UPDATE factory_run SET started_at=ended_at-interval '9 seconds' WHERE run_id=?",result.runId());
        bridge.accept(result);var usage=store.load(id).progress();assertFalse(usage.usageUnknown());assertEquals(7,usage.costMillicents());assertEquals(9,usage.wallSeconds());assertEquals(1,usage.calls());
        assertEquals(200,transitions.resume(id,store.history(id).size(),true).status());bridge.accept(result);
        var resumed=store.load(id).progress();assertEquals(usage.costMillicents(),resumed.costMillicents());assertEquals(usage.wallSeconds(),resumed.wallSeconds());assertEquals(usage.calls(),resumed.calls());
    }
    @Test void ceilingChangesBeforeDeliveryPreventTheProposal() throws Exception {
        // Explicit test-only verification driver, independent from the production missing-verifier case above.
        QuarkusMock.installMockForType(new WorkPhaseCapability(){@Override public boolean available(WorkItemEvent item,String phase){return Set.of("build","verify","deliver").contains(phase);}},WorkPhaseCapability.class);
        String id=build();saga.on(priorBuildResult());var verifying=store.load(id);assertEquals("verify",verifying.phase());assertEquals("active",verifying.workflowStatus());
        var high=profiles.get("autonomous");var modes=new EnumMap<WorkPolicy.Phase,String>(WorkPolicy.Phase.class);modes.putAll(high.modes());modes.put(WorkPolicy.Phase.DELIVER,"off");
        UUID ceiling=UUID.randomUUID();extraProfiles.add(ceiling);policies.createVersion(new WorkPolicy.Profile(ceiling,"TEST-delivery-off-"+ceiling,1,1_000_000_499,modes,high.limits()));
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mapping=new HashMap<>();policy.mappings().forEach((label,p)->mapping.put(label,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(ceiling,1),mapping));
        assertTrue(sources.get(source).orElseThrow().enabled());assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
        transitions.complete(id,new WorkItemTransitions.PhaseResult(verifying.progress().attemptId(),true,1,1,1));
        given().get("/api/work-items/"+id).then().statusCode(200).body("phase",is("deliver"),"reason",is("deliver_off"),"workflowStatus",is("not_eligible"));
        assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND phase='deliver'",id));
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=? AND pr_number IS NOT NULL",id));
    }
}
