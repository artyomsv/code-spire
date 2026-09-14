package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import io.quarkus.test.junit.*;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/** TEST-only verification driver. Publisher execution is proven separately against the real remote. */
@QuarkusTest
@TestSecurity(user="TEST-delivery-admin",roles="spire-admin")
class WorkDeliveryIT extends WorkPreparedFixture {
    @Inject WorkDelivery delivery;
    @Inject WorkRunTransport runTransport;
    @Inject RunResultSaga saga;
    @Inject FactoryRunProjection runs;
    @Inject WorkItemControl control;
    final List<RunCommand.PublishWorkRun> permits=new ArrayList<>();
    final List<String> cancelled=new ArrayList<>();
    RunLaunch.Outcome publicationOutcome=new RunLaunch.Dispatched();
    String pulls(){return "/repos/"+scope+"/pulls";}

    @BeforeEach void priorPhaseDriver() {
        var capability=new WorkPhaseCapability(){@Override public boolean available(WorkItemEvent item,String phase){return "verify".equals(phase) || super.available(item,phase);}};
        capability.runs=runTransport;capability.delivery=delivery;
        QuarkusMock.installMockForType(capability,WorkPhaseCapability.class);
        QuarkusMock.installMockForType(new WorkPublicationTransport(){
            @Override public RunLaunch.Outcome publish(RunCommand.PublishWorkRun command){
                try {assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE run_id=? AND state='publishing' AND permit IS NOT NULL",command.runId()),"The claim precedes the control write");}
                catch(Exception failure){throw new AssertionError(failure);}
                permits.add(command);return publicationOutcome;
            }
            @Override public void cancel(String id){cancelled.add(id);}
        },WorkPublicationTransport.class);
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson("[]")));
    }
    String buildForVerification(String profileName) throws Exception {
        String id=admit(profileName,55);register(id);
        if("assisted".equals(profileName)) {
            var gate=store.load(id).gate();assertNotNull(gate);
            assertEquals(200,transitions.answer(gate.id(),gate.version(),"TEST-approve-plan",true,"TEST prepared evidence","TEST-delivery-admin").status());
        }
        dispatcher.drain();var command=heldCommands.getLast();
        saga.on(new RunResult.RunWorkReady(command.runId(),command.work(),"b".repeat(40),List.of("TEST-result"),Map.of("INPUT",7L),9));
        assertEquals("verify",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());return id;
    }
    UUID verify(String id) {
        var item=store.load(id);var progress=item.progress();
        assertEquals(200,transitions.complete(id,new WorkItemTransitions.PhaseResult(progress.attemptId(),true,1,0,0,true,
                progress.execution().verified(progress.attemptId()))).status());
        assertEquals("deliver",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
        return store.load(id).progress().attemptId();
    }
    void publisherFinished(String id) {
        var execution=store.load(id).progress().execution();var run=runs.find(execution.runId()).orElseThrow();
        saga.on(new RunResult.RunFinished(run.runId(),"refs/heads/"+run.branch(),List.of("TEST-result"),List.of(),Map.of("INPUT",7L),false));
    }
    String proposed(boolean draft){return "{\"number\":901,\"html_url\":\"https://forge.example.test/TEST-pull/901\",\"draft\":"+draft+"}";}
    void deliveryProof(String profileName,boolean draft) throws Exception {
        String id=buildForVerification(profileName);UUID attempt=verify(id);var execution=store.load(id).progress().execution();
        forge.stubFor(post(urlPathEqualTo(pulls())).willReturn(okJson(proposed(draft))));
        delivery.advance(attempt);assertEquals(1,permits.size());
        assertEquals(execution.build(),permits.getFirst().permit().work());assertEquals(execution.head(),permits.getFirst().permit().head());
        assertEquals(attempt,permits.getFirst().permit().deliveryAttemptId());assertTrue(permits.getFirst().permit().protectedPaths().contains("TEST-sensitive/**"));
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
        publisherFinished(id);delivery.advance(attempt);
        forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())).withRequestBody(matchingJsonPath("$.draft",equalTo(Boolean.toString(draft)))));
        var item=store.load(id);assertEquals("review",item.phase());assertEquals("review_capability_unavailable",item.reason());
        assertEquals(Boolean.valueOf(draft),item.progress().execution().pullRequest().draft());
        assertEquals(execution.verificationAttempt(),item.progress().execution().verificationAttempt());assertEquals(1,item.progress().calls());
        assertEquals(1,runCount(id));assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='delivered'",id));
        delivery.drain();assertEquals(1,permits.size());forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void deliversANativeDraftAfterExplicitTestVerification() throws Exception {deliveryProof("assisted",true);}
    @Test void deliversARegularRequestAfterExplicitTestVerification() throws Exception {deliveryProof("autonomous",false);}
    @Test void noVerificationMeansNoPublicationPermit() throws Exception {
        String id=buildForVerification("autonomous");delivery.drain();assertTrue(permits.isEmpty());
        assertEquals(0,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?",id));forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void verificationMustNameTheRetainedCheckpoint() throws Exception {
        String id=buildForVerification("autonomous");var item=store.load(id);var original=item.progress().execution();
        var wrong=new WorkExecution(original.runId(),original.build(),"c".repeat(40),item.progress().attemptId(),null,null);
        var result=transitions.complete(id,new WorkItemTransitions.PhaseResult(item.progress().attemptId(),true,1,0,0,true,wrong));
        assertEquals(409,result.status());assertEquals("phase_evidence_mismatch",result.reason());assertEquals("verify",store.load(id).phase());
        assertEquals(0,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?",id));
    }
    @Test void aSuccessfulFlagWithoutVerificationEvidenceCannotAdvance() throws Exception {
        String id=buildForVerification("autonomous");var item=store.load(id);
        var result=transitions.complete(id,new WorkItemTransitions.PhaseResult(item.progress().attemptId(),true,1,0,0));
        assertEquals(409,result.status());assertEquals("phase_evidence_mismatch",result.reason());assertEquals("verify",store.load(id).phase());
    }
    @Test void anUnprocessedCheckpointCannotAuthorizePublication() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        execute("UPDATE work_run_effect SET ready_processed=false WHERE work_item_id=?",id);
        assertDeliveryRefused(id,attempt,"verified_checkpoint_not_observed");
    }
    @Test void anotherObservedHeadCannotAuthorizePublication() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        execute("UPDATE factory_run SET checkpoint_head=repeat('c',40) WHERE work_item_id=?",id);
        assertDeliveryRefused(id,attempt,"verified_checkpoint_not_observed");
    }
    @Test void anIncompleteVerificationCannotAuthorizePublication() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        execute("UPDATE work_phase_attempt SET state='started' WHERE id=?",store.load(id).progress().execution().verificationAttempt());
        assertDeliveryRefused(id,attempt,"verified_checkpoint_not_observed");
    }
    @Test void anotherCompletedPhaseCannotStandInForVerification() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        execute("UPDATE work_phase_attempt SET phase='spec' WHERE id=?",store.load(id).progress().execution().verificationAttempt());
        assertDeliveryRefused(id,attempt,"verified_checkpoint_not_observed");
    }
    @Test void aRunWithoutTheHeldReadyStateCannotReceiveAPermit() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        execute("UPDATE factory_run SET status='queued' WHERE work_item_id=?",id);
        assertDeliveryRefused(id,attempt,"held_run_not_ready");
    }
    void assertDeliveryRefused(String id,UUID attempt,String reason) throws Exception {
        delivery.advance(attempt);assertEquals(reason,store.load(id).reason());assertEquals("stopped",store.load(id).workflowStatus());
        assertTrue(permits.isEmpty());assertFalse(store.load(id).progress().reserved());
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='refused'",id));
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void aPermitIsNotResentWhenTheBrokerOutcomeIsUnknown() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        publicationOutcome=new RunLaunch.Uncertain(new IllegalStateException("TEST missing broker ack"));
        delivery.advance(attempt);delivery.advance(attempt);assertEquals(1,permits.size());
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='publishing' AND reason='publication_dispatch_uncertain'",id));
    }
    @Test void aDefinitePermitMissRearmsOnlyTheSameDelivery() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);
        publicationOutcome=new RunLaunch.DefiniteMiss(new IllegalStateException("TEST rejected before broker write"));
        delivery.advance(attempt);assertEquals(1,permits.size());
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='pending' AND reason='publication_dispatch_missed'",id));
        publicationOutcome=new RunLaunch.Dispatched();delivery.advance(attempt);assertEquals(2,permits.size());
        assertEquals(permits.getFirst().runId(),permits.getLast().runId());assertEquals(permits.getFirst().permit().work(),permits.getLast().permit().work());
        assertEquals(attempt,permits.getLast().permit().deliveryAttemptId());assertEquals(1,runCount(id));
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void anUnknownNativeStateCannotCompleteRegularDelivery() throws Exception {
        rejectObservedState("{\"number\":901,\"html_url\":\"https://forge.example.test/TEST-pr/901\"}");
    }
    @Test void anObservedDraftCannotCompleteRegularDelivery() throws Exception { rejectObservedState(proposed(true)); }
    void rejectObservedState(String response) throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);publisherFinished(id);
        forge.stubFor(post(urlPathEqualTo(pulls())).willReturn(okJson(response)));delivery.advance(attempt);
        assertEquals("requested_pr_state_not_observed",store.load(id).reason());assertEquals("stopped",store.load(id).workflowStatus());
        assertNull(store.load(id).progress().execution().pullRequest());assertEquals(1,permits.size());
        forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void aDifferentPublishedBranchCannotAuthorizeAProposal() throws Exception { rejectPublication(false); }
    @Test void anUnfinishedAgentCannotAuthorizeAProposal() throws Exception { rejectPublication(true); }
    void rejectPublication(boolean unobserved) throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);
        var run=runs.find(store.load(id).progress().execution().runId()).orElseThrow();
        saga.on(new RunResult.RunFinished(run.runId(),"refs/heads/"+(unobserved?run.branch():"TEST-another-branch"),List.of("TEST-result"),List.of(),Map.of("INPUT",7L),false).withAgentUnobserved(unobserved));
        assertEquals(unobserved?"delivered_unfinished":"succeeded",runs.find(run.runId()).orElseThrow().status());
        delivery.advance(attempt);assertEquals("publication_failed",store.load(id).reason());assertEquals("stopped",store.load(id).workflowStatus());
        assertEquals(1,permits.size());forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    void disableDelivery() {
        var high=profiles.get("autonomous");UUID lower=UUID.randomUUID();extraProfiles.add(lower);
        var modes=new EnumMap<WorkPolicy.Phase,String>(high.modes());modes.put(WorkPolicy.Phase.DELIVER,"off");
        policies.createVersion(new WorkPolicy.Profile(lower,"TEST-no-delivery-"+lower,1,1_000_000_492,modes,high.limits()));
        var policy=policies.get(repository);Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();
        policy.mappings().forEach((key,p)->mappings.put(key,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(lower,1),mappings));
        assertTrue(sources.get(source).orElseThrow().enabled());
        assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
    }
    @Test void aChangedCeilingPreventsTheFirstPermit() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);disableDelivery();
        delivery.advance(attempt);assertTrue(permits.isEmpty());assertEquals("policy_changed_before_delivery",store.load(id).reason());
        assertFalse(store.load(id).progress().reserved());forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void changedLabelsRevokeDeliveryWithoutChangingThePolicyRevision() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);var admitted=store.load(id);
        var changed=mapper.createObjectNode().put("id",50055).put("number",55).put("repository_url",forge.baseUrl()+"/repos/"+scope)
                .put("html_url",admitted.issue().link().toString()).put("title","TEST-prepared-task").put("body","TEST-identical task").put("state","open");
        changed.putArray("labels");forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues/55")).willReturn(okJson(changed.toString())));
        var observed=transitions.observe(admitted);assertNull(observed.evidence().failure());assertNull(observed.artifacts().failure());
        assertEquals(admitted.policyRevision(),observed.policy().revision(),"Only label-derived selection changes");
        assertNotEquals(admitted.policy(),transitions.select(observed,admitted));
        assertTrue(observed.source().enabled());assertTrue(observed.source().allowedActors().contains("900123"));
        assertDeliveryRefused(id,attempt,"policy_changed_before_delivery");
    }
    @Test void aNewPolicyRevisionRequiresANewDecisionEvenWithTheSameSelection() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);var admitted=store.load(id);var policy=policies.get(repository);
        Map<String,WorkPolicyRegistry.Pin> mappings=new HashMap<>();policy.mappings().forEach((key,p)->mappings.put(key,new WorkPolicyRegistry.Pin(p.id(),p.version())));
        var high=profiles.get("autonomous");mappings.put("TEST-unused-label",new WorkPolicyRegistry.Pin(high.id(),high.version()));
        policies.save(repository,new WorkPolicyRegistry.Input(policy.revision(),new WorkPolicyRegistry.Pin(high.id(),high.version()),mappings));
        var observed=transitions.observe(admitted);assertEquals(admitted.policy(),transitions.select(observed,admitted),"The effective selection itself remains identical");
        assertNotEquals(admitted.policyRevision(),observed.policy().revision());assertTrue(observed.source().enabled());assertTrue(observed.source().allowedActors().contains("900123"));
        assertDeliveryRefused(id,attempt,"policy_changed_before_delivery");
    }
    @Test void aChangedCeilingCancelsAnOutstandingPermit() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);disableDelivery();
        assertTrue(Instant.now().isBefore(permits.getFirst().permit().expiresAt()),"The policy change, not expiry, must revoke authority");
        delivery.advance(attempt);assertEquals(List.of(permits.getFirst().runId()),cancelled);assertEquals(1,permits.size());
        assertEquals("policy_changed_before_delivery",store.load(id).reason());assertFalse(store.load(id).progress().reserved());
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void aChangedCeilingAfterPushPreventsTheProposal() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);publisherFinished(id);disableDelivery();
        delivery.advance(attempt);assertEquals("policy_changed_before_delivery",store.load(id).reason());assertEquals(1,permits.size());
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void anExpiredUnobservedPermitStopsDeliveryAndRequestsCancellation() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);
        Instant expiry=permits.getFirst().permit().expiresAt();QuarkusMock.installMockForType(new WorkClock(){@Override public Instant now(){return expiry;}},WorkClock.class);
        delivery.advance(attempt);assertEquals(List.of(permits.getFirst().runId()),cancelled);assertEquals(1,permits.size());
        assertEquals("publication_permit_expired",store.load(id).reason());assertFalse(store.load(id).progress().reserved());
        forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void proposalRecoveryReadsTheExistingRequestWithoutRepeatingThePost() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);publisherFinished(id);
        forge.stubFor(post(urlPathEqualTo(pulls())).inScenario("TEST-lost-response").willSetStateTo("TEST-written")
                .willReturn(aResponse().withStatus(503).withBody("TEST response lost")));
        forge.stubFor(get(urlPathEqualTo(pulls())).inScenario("TEST-lost-response").whenScenarioStateIs("TEST-written").willReturn(aResponse().withStatus(503)));
        delivery.advance(attempt);assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='proposing'",id));
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson("["+proposed(false)+"]")));
        delivery.advance(attempt);assertEquals("review",store.load(id).phase());
        forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));assertEquals(1,permits.size());
    }
    @Test void takeoverCancelsPendingDeliveryAndDurablyRequestsTheExactRunHold() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);var execution=store.load(id).progress().execution();
        control.suspend(id,"scm","TEST-takeover-before-permit","900123",execution.head());
        delivery.advance(attempt);
        assertEquals("suspended",store.load(id).workflowStatus());assertTrue(permits.isEmpty());
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE attempt_id=? AND state='refused'",attempt));
        assertEquals(1,count("SELECT count(*) FROM work_run_hold_outbox WHERE run_id=? AND work_item_id=? AND generation=? AND build_attempt_id=? AND preparation_binding=?",
                execution.runId(),id,execution.build().generation(),execution.build().buildAttemptId(),execution.build().preparationBinding()));
        assertFalse(store.load(id).progress().reserved());forge.verify(0,postRequestedFor(urlPathEqualTo(pulls())));
    }
    @Test void takeoverPreservesAnInFlightProposalOutcomeWithoutResumingOrPostingAgain() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);publisherFinished(id);
        forge.stubFor(post(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(503)));
        delivery.advance(attempt);
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE attempt_id=? AND state='proposing'",attempt));
        control.suspend(id,"scm","TEST-takeover-during-proposal","900123","b".repeat(40));
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson("["+proposed(false)+"]")));
        var target=io.quarkus.arc.ClientProxy.unwrap(delivery);var original=target.runs;
        var bothObserved=new java.util.concurrent.CountDownLatch(2);
        target.runs=new FactoryRunProjection(){
            @Override public Optional<RunView> find(String run){return original.find(run);}
            @Override public void pullRequestOpened(String run,long number,String url){
                original.pullRequestOpened(run,number,url);bothObserved.countDown();
                try{assertTrue(bothObserved.await(20,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException failure){throw new AssertionError(failure);}
            }
        };
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->delivery.advance(attempt));var second=pool.submit(()->delivery.advance(attempt));
            first.get(30,java.util.concurrent.TimeUnit.SECONDS);second.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }finally{target.runs=original;}
        delivery.advance(attempt);
        var item=store.load(id);assertEquals("suspended",item.workflowStatus());assertNotNull(item.progress().execution().pullRequest());
        assertEquals(901,item.progress().execution().pullRequest().number());
        assertEquals(1,store.history(id).stream().map(e->(WorkItemEvent)e.payload()).filter(e->"PUBLICATION_OBSERVED".equals(e.milestone())).count());
        assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE attempt_id=? AND state='delivered'",attempt));
        forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));assertEquals(1,permits.size());
    }
    @Test void aStalePublicationReaderCannotRearmAClaimedProposal() throws Exception {
        String id=buildForVerification("autonomous");UUID attempt=verify(id);delivery.advance(attempt);publisherFinished(id);
        var reached=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        var target=io.quarkus.arc.ClientProxy.unwrap(delivery);var original=target.runs;
        var captured=new java.util.concurrent.atomic.AtomicBoolean();
        target.runs=new FactoryRunProjection(){
            @Override public Optional<RunView> find(String run) {
                if(Thread.currentThread().getName().equals("TEST-stale-publication-reader") && captured.compareAndSet(false,true)) {
                    var snapshot=original.find(run);reached.countDown();
                    try{assertTrue(resume.await(30,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}
                    return snapshot;
                }
                return original.find(run);
            }
            @Override public void pullRequestOpened(String run,long number,String url){original.pullRequestOpened(run,number,url);}
        };
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor(task->new Thread(task,"TEST-stale-publication-reader"));
        try {
            var stale=pool.submit(()->delivery.advance(attempt));assertTrue(reached.await(30,java.util.concurrent.TimeUnit.SECONDS));
            // The first caller commits a proposal claim, then loses its response. The stale
            // caller must not change proposing back to pushed and perform a second POST.
            forge.stubFor(post(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(503)));
            forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson("[]")));
            delivery.advance(attempt);assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='proposing'",id));
            forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));
            int readsAfterClaim=forge.findAll(getRequestedFor(urlPathEqualTo(pulls()))).size();
            resume.countDown();stale.get(30,java.util.concurrent.TimeUnit.SECONDS);
            forge.verify(1,postRequestedFor(urlPathEqualTo(pulls())));
            assertEquals(readsAfterClaim,forge.findAll(getRequestedFor(urlPathEqualTo(pulls()))).size(),"A stale publication observation stops before another forge round trip");
            assertEquals(1,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND state='proposing'",id));
        } finally {resume.countDown();pool.shutdownNow();pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS);target.runs=original;}
    }
}
