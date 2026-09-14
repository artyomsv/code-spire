package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.RunResultSaga;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest @TestSecurity(user="TEST-prepared-admin",roles="spire-admin")
class WorkRunDispatchTest extends WorkPreparedFixture {
    @Inject RunResultSaga saga;
    @Inject dev.codespire.orchestrator.factory.WorkRunAssembly assembly;
    String ready() throws Exception {String id=admit("autonomous",56);register(id);return id;}
    @Test void concurrentDispatchClaimsPublishOnlyOnce() throws Exception {
        String id=ready();UUID attempt=store.load(id).progress().attemptId();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->dispatcher.dispatch(attempt));var second=executor.submit(()->dispatcher.dispatch(attempt));
            first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,dispatched.size());assertEquals(1,runCount(id));
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='sent'",id));
    }
    @Test void uncertainDispatchWaitsForAResultInsteadOfResending() throws Exception {
        String id=ready();brokerFailure=BrokerAckFailure.notAcknowledged("TEST-lost-ack",null);dispatcher.drain();dispatcher.drain();
        assertEquals(1,dispatched.size());assertEquals(1,runCount(id));
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='uncertain'",id));
        saga.on(new RunResult.RunStarted(dispatched.getFirst().runId(),"TEST-unit"));
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='sent'",id));
        dispatcher.drain();assertEquals(1,dispatched.size());
    }
    @Test void anExplicitNeverRanResolutionRearmsTheSameAssociation() throws Exception {
        String id=ready();brokerFailure=BrokerAckFailure.notAcknowledged("TEST-lost-ack",null);dispatcher.drain();
        String run=dispatched.getFirst().runId();UUID attempt=store.load(id).progress().attemptId();
        given().contentType("application/json").body(Map.of("neverRan",true)).post("/api/runs/"+run+"/dispatch-resolution").then().statusCode(204);
        brokerFailure=null;dispatcher.drain();
        assertEquals(2,dispatched.size());assertEquals(run,dispatched.getLast().runId());assertEquals(1,runCount(id));
        assertEquals(attempt,store.load(id).progress().attemptId());assertEquals(1,store.load(id).progress().runs());
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='sent'",id));
    }
    @Test void aDefiniteBrokerMissReusesTheDurableAttempt() throws Exception {
        String id=ready();brokerFailure=BrokerAckFailure.rejected("TEST-never-sent",new org.apache.kafka.common.errors.SerializationException("TEST-invalid"));
        dispatcher.drain();assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='pending'",id));
        brokerFailure=null;dispatcher.drain();assertEquals(2,dispatched.size());assertEquals(dispatched.getFirst().runId(),dispatched.getLast().runId());
        assertEquals(1,runCount(id));assertEquals(1,store.load(id).progress().runs());
    }
    @Test void changedArtifactsRefuseTheUnstartedBuild() throws Exception {
        String id=ready();stubArtifact(71,57001,"TEST-replaced specification");
        assertDoesNotThrow(dispatcher::drain,"Changed evidence must produce a durable refusal, not escape into scheduler retry");
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("artifacts_changed",store.load(id).reason());
        assertFalse(store.load(id).progress().reserved());assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='refused'",id));
    }
    @Test void losingPublicationSupportRefusesAnAlreadyAdmittedBuild() throws Exception {
        String id=ready();publicationSupported=false;dispatcher.drain();
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("publication_hold_unavailable",store.load(id).reason());assertFalse(store.load(id).progress().reserved());
    }
    @Test void removingTheActorRefusesTheUnstartedBuild() throws Exception {
        String id=ready();execute("DELETE FROM work_source_actor WHERE source_id=?",source);dispatcher.drain();
        assertTrue(sources.get(source).orElseThrow().enabled());assertEquals(0,runCount(id));assertEquals("policy_changed_before_dispatch",store.load(id).reason());
    }
    @Test void unavailableCurrentEvidenceCannotDispatch() throws Exception {
        String id=ready();forge.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/repos/"+scope+"/issues/56"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(503)));
        dispatcher.drain();assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("stopped",store.load(id).workflowStatus());
        assertEquals("tracker_unavailable",store.load(id).reason());assertFalse(store.load(id).progress().reserved());
    }
    @Test void missingFactoryBindingCannotFallBackToTheTrackerAccount() throws Exception {
        String id=ready();execute("DELETE FROM repository_account WHERE repository_id=? AND role='FACTORY'",repository);dispatcher.drain();
        assertTrue(sources.get(source).orElseThrow().enabled());assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("build_configuration_unavailable",store.load(id).reason());
    }
    @Test void unpricedModelsCannotStartABuild() throws Exception {
        String id=ready();execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);dispatcher.drain();
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("build_configuration_unavailable",store.load(id).reason());
    }
    @Test void aRemovedHarnessImageProducesADurableRefusal() throws Exception {
        String id=ready();
        // Replace only deployment configuration; the existing M2 parser must raise its real validation error.
        try(var ignored=dev.codespire.orchestrator.factory.WorkRunTestConfiguration.withoutImages(assembly)) {
            assertDoesNotThrow(dispatcher::drain,"A changed deployment must refuse durably instead of escaping into scheduler retries");
            dispatcher.drain();
            assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
            assertEquals("build_configuration_unavailable",store.load(id).reason());
            assertEquals("stopped",store.load(id).workflowStatus());assertFalse(store.load(id).progress().reserved());
            assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='refused'",id));
        }
    }
    @Test void theCurrentCeilingIsCheckedAgainBeforeDispatch() throws Exception {
        String id=ready();var high=profiles.get("autonomous");
        UUID lower=UUID.randomUUID();extraProfiles.add(lower);var modes=new EnumMap<WorkPolicy.Phase,String>(high.modes());modes.put(WorkPolicy.Phase.BUILD,"off");
        policies.createVersion(new WorkPolicy.Profile(lower,"TEST-no-build-"+lower,1,1_000_000_490,modes,high.limits()));
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();policy.mappings().forEach((key,p)->mappings.put(key,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(lower,1),mappings));
        dispatcher.drain();assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("policy_changed_before_dispatch",store.load(id).reason());
    }
    @Test void intakeCannotReauthorizeAPendingBuildByObservingANewerCeiling() throws Exception {
        String id=ready();var high=profiles.get("autonomous");UUID lower=UUID.randomUUID();extraProfiles.add(lower);
        var modes=new EnumMap<WorkPolicy.Phase,String>(high.modes());modes.put(WorkPolicy.Phase.BUILD,"off");
        policies.createVersion(new WorkPolicy.Profile(lower,"TEST-observed-no-build-"+lower,1,1_000_000_491,modes,high.limits()));
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();policy.mappings().forEach((key,p)->mappings.put(key,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(lower,1),mappings));
        var observed=transitions.observe(store.load(id));store.reconcile(observed.source(),observed.policy().revision(),observed.evidence(),"TEST-new-ceiling-observed");
        assertEquals("active",store.load(id).workflowStatus());assertEquals("off",store.load(id).policy().effective().get(WorkPolicy.Phase.BUILD));
        assertTrue(sources.get(source).orElseThrow().enabled());assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
        dispatcher.drain();assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("policy_changed_before_dispatch",store.load(id).reason());
    }
}
