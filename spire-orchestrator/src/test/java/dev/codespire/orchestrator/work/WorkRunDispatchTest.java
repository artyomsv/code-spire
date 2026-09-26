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
    @Inject WorkItemControl control;
    @Test void takeoverInvalidatesTheUnstartedBuildBeforeAnyDispatcherRecheck()throws Exception {
        String id=ready();assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='pending'",id));
        control.suspend(id,"tracker","TEST-before-dispatch","900123",null);
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='refused'",id));
        assertEquals("suspended",store.load(id).workflowStatus());assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
    }
    @Test void dispatchRecordsServingIdentityAndPreservesTrackerIdentityFromAdmission() throws Exception {
        execute("UPDATE scm_provider SET bot_account_id='900001' WHERE id=?",account);
        String id=ready();assertEquals("900001",store.load(id).control().trackerActor());
        execute("UPDATE scm_provider SET bot_account_id='900002' WHERE id=?",account);
        dispatcher.drain();var item=store.load(id);var command=heldCommands.getLast();
        assertEquals(command.runId(),item.control().runId());assertEquals(command.work(),item.control().build());
        assertEquals(command.execution().branch(),item.control().branch());
        assertEquals("900002",item.control().factoryActor());assertEquals("900001",item.control().trackerActor());
        execute("UPDATE scm_provider SET bot_account_id='900003',bot_username='TEST-renamed-again' WHERE id=?",account);
        assertEquals("900002",store.load(id).control().factoryActor());
    }
    /** The level the approved binding hashed is the level the build is sent with (M3.5 part M). */
    @Test void theApprovedThinkingLevelIsTheOneTheBuildRunsAt() throws Exception {
        codexRuns(model,"medium","high");
        String id=admit("autonomous",57);var plain=preparation("TEST-prepared-admin");
        var atLevel=new WorkPreparation(plain.specification(),plain.plan(),plain.baseBranch(),plain.baseCommit(),plain.harness(),
                plain.model(),plain.registeredBy(),WorkPreparation.EFFORT_BINDING,"high");
        var outcome=transitions.prepare(id,store.history(id).size(),atLevel);assertEquals(200,outcome.status(),outcome.reason());
        dispatcher.drain();
        assertEquals("high",heldCommands.getLast().execution().reasoningEffort());
        assertEquals(atLevel.binding(),heldCommands.getLast().work().preparationBinding());
        // The exact image the checked list was read from, not the tag (review of PR #167).
        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000",heldCommands.getLast().execution().agentImage());
    }
    /** Approved while codex ran this model; by dispatch its image no longer does (review of PR #167). */
    @Test void aModelTheHarnessNoLongerRunsCannotStartABuild() throws Exception {
        String id=ready();codexRuns("TEST-some-other-model","medium");
        dispatcher.drain();
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
        assertEquals("model_not_run_by_harness",store.load(id).reason());
    }
    @Inject dev.codespire.orchestrator.factory.HarnessCatalogues catalogues;
    @Inject dev.codespire.orchestrator.factory.FactoryConfig factoryConfig;
    private void codexRuns(String slug,String... levels) {
        catalogues.record(new dev.codespire.contract.event.HarnessImageResult.Described("TEST-request","codex",
                factoryConfig.agentImage().get("codex"),dev.codespire.contract.event.HarnessImageResult.Status.OK,
                List.of(new dev.codespire.contract.event.HarnessImageResult.Model(slug,slug,"medium",List.of(levels),true,1)),
                "TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000"));
    }
    @org.junit.jupiter.api.AfterEach void forgetTheCatalogue() throws Exception {executeWith("DELETE FROM harness_catalogue");}
    @Inject dev.codespire.encryption.EncryptionService encryption;

    /**
     * A build approved to pay with a subscription leases a seat and hands the agent the sign-in with its
     * refresh token emptied — never the whole stored file (M3.5 part F, design §5.6).
     */
    @Test void aSubscriptionBuildLeasesASeatAndHandsOverNoRefreshToken() throws Exception {
        UUID seat;
        try(var c=dataSource.getConnection()) {
            seat=pool.addSubscription(c,"TEST-dispatch-seat-"+UUID.randomUUID(),"codex",
                    "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"TEST-access\",\"refresh_token\":\"TEST-refresh-must-not-leave\","
                    +"\"account_id\":\"TEST-account-"+UUID.randomUUID()+"\"}}");
        }
        try {
            String id=admit("autonomous",58);var plain=preparation("TEST-prepared-admin");
            var paying=new WorkPreparation(plain.specification(),plain.plan(),plain.baseBranch(),plain.baseCommit(),plain.harness(),
                    plain.model(),plain.registeredBy(),WorkPreparation.PAY_WITH_BINDING,null,PayWith.SUBSCRIPTION);
            var outcome=transitions.prepare(id,store.history(id).size(),paying);assertEquals(200,outcome.status(),outcome.reason());

            dispatcher.drain();

            var command=heldCommands.getLast().execution();
            assertTrue(command.harnessSignIn(),"the worker must write a file, not pipe a key");
            String handedOver=encryption.decryptString(command.harnessCredential(),dev.codespire.contract.command.RunCommand.harnessCredentialAad(command.runId()));
            assertFalse(handedOver.contains("TEST-refresh-must-not-leave"),"the refresh token never leaves the orchestrator");
            assertTrue(handedOver.contains("TEST-access"));
            assertEquals(1,count("SELECT count(*) FROM factory_run WHERE run_id=? AND harness_credential_id=?",command.runId(),seat),
                    "the run names the seat that paid");
            assertEquals(1,count("SELECT count(*) FROM factory_run WHERE run_id=? AND paid_by='SUBSCRIPTION'",command.runId()),
                    "the run records how it paid, where a re-arm cannot erase it");
        } finally { pool.remove(seat); }
    }

    /** A stored sign-in that cannot be read is refused by name, and nothing reaches a worker. */
    @Test void aSubscriptionBuildWhoseSignInCannotBeReadDispatchesNothing() throws Exception {
        UUID seat;
        try(var c=dataSource.getConnection()) {
            seat=pool.addSubscription(c,"TEST-dispatch-unreadable-"+UUID.randomUUID(),"codex",
                    "{\"auth_mode\":\"TEST\",\"tokens\":{\"account_id\":\"TEST-account-"+UUID.randomUUID()+"\"}}");
            // The stored file becomes unreadable after it was identified: the hand-over fails at assembly.
            try(var ps=c.prepareStatement("UPDATE harness_credential SET api_key=? WHERE id=?")) {
                ps.setString(1,encryption.encryptString("TEST-not-a-sign-in","harness-credential:"+seat));
                ps.setObject(2,seat);ps.executeUpdate();
            }
        }
        try {
            String id=admit("autonomous",59);var plain=preparation("TEST-prepared-admin");
            var paying=new WorkPreparation(plain.specification(),plain.plan(),plain.baseBranch(),plain.baseCommit(),plain.harness(),
                    plain.model(),plain.registeredBy(),WorkPreparation.PAY_WITH_BINDING,null,PayWith.SUBSCRIPTION);
            var outcome=transitions.prepare(id,store.history(id).size(),paying);assertEquals(200,outcome.status(),outcome.reason());
            int before=heldCommands.size();

            dispatcher.drain();

            assertEquals(before,heldCommands.size(),"nothing was dispatched");
        } finally { pool.remove(seat); }
    }
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
        // The reason is the one the assembly gave. It used to be one word for every cause, which left an
        // approved plan stopped with nothing an operator could act on.
        assertTrue(sources.get(source).orElseThrow().enabled());assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());assertEquals("factory_account_unavailable",store.load(id).reason());
    }
    @Test void unpricedModelsCannotStartABuild() throws Exception {
        String id=ready();execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);dispatcher.drain();
        // And it names every type the harness reports but this model cannot price, so the operator is
        // told which rates to enter rather than being sent to look for them.
        assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
        assertEquals("model_pricing_incomplete:INPUT,CACHED_INPUT,CACHE_WRITE,OUTPUT,REASONING",store.load(id).reason());
    }
    /** A model switched off in the catalogue is not one a repository may keep calling. */
    @Test void aDisabledModelCannotStartABuild() throws Exception {
        String id=ready();execute("UPDATE llm_model SET enabled=FALSE WHERE id=?",modelId);
        try {
            dispatcher.drain();
            assertEquals(0,runCount(id));assertTrue(dispatched.isEmpty());
            assertEquals("model_disabled",store.load(id).reason());
        } finally { execute("UPDATE llm_model SET enabled=TRUE WHERE id=?",modelId); }
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
