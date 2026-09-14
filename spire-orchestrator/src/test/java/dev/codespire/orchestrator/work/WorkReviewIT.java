package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.*;
import dev.codespire.contract.review.*;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.pipeline.DomainEventSink;
import io.quarkus.test.junit.*;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user="TEST-review-admin",roles="spire-admin")
class WorkReviewIT extends WorkPreparedFixture {
    @Inject WorkDelivery delivery;
    @Inject WorkReview observer;
    @Inject WorkRunTransport transport;
    @Inject RunResultSaga saga;
    @Inject FactoryRunProjection runs;
    @Inject ReviewProjection reviews;
    @Inject DomainEventSink domain;
    UUID reviewer;
    String review;
    @BeforeEach void explicitPriorVerificationAndReviewer() {
        // Only verification is supplied by this TEST driver. Delivery and review observation remain production.
        var capability=new WorkPhaseCapability(){@Override public boolean available(WorkItemEvent item,String phase){return "verify".equals(phase) || super.available(item,phase);}};
        capability.runs=transport;capability.delivery=delivery;QuarkusMock.installMockForType(capability,WorkPhaseCapability.class);
        QuarkusMock.installMockForType(new WorkPublicationTransport(){@Override public RunLaunch.Outcome publish(RunCommand.PublishWorkRun command){return new RunLaunch.Dispatched();}},WorkPublicationTransport.class);
        reviewer=UUID.fromString(providers.create(new ProviderInput("TEST-reviewer-"+UUID.randomUUID(),"github",forge.baseUrl(),
                "bearer",null,"TEST-reviewer-secret","TEST-reviewer",true,List.of(),"TEST-reviewer",null,"REVIEWER")).id());
        var repositoryRow=repositories.get(repository).orElseThrow();var repo=sources.get(source).orElseThrow().repository();
        repositories.update(repository,repositoryRow.revision(),new RepositoryInput("github",forge.baseUrl(),repo.workspace(),repo.slug(),true,reviewer,account));
        forge.stubFor(get(urlPathEqualTo("/repos/"+scope+"/pulls")).willReturn(okJson("[]")));
        forge.stubFor(post(urlPathEqualTo("/repos/"+scope+"/pulls")).willReturn(okJson("{\"number\":901,\"html_url\":\"https://forge.example.test/TEST-pull/901\",\"draft\":false}")));
    }
    String delivered() throws Exception {
        String id=admit("autonomous",55);register(id);dispatcher.drain();var command=heldCommands.getLast();
        saga.on(new RunResult.RunWorkReady(command.runId(),command.work(),"b".repeat(40),List.of("TEST-file"),Map.of("INPUT",7L),9));
        var verify=store.load(id).progress();assertEquals(200,transitions.complete(id,new WorkItemTransitions.PhaseResult(verify.attemptId(),true,1,0,0,true,verify.execution().verified(verify.attemptId()))).status());
        UUID attempt=store.load(id).progress().attemptId();delivery.advance(attempt);
        saga.on(new RunResult.RunFinished(command.runId(),"refs/heads/"+command.execution().branch(),List.of("TEST-file"),List.of(),Map.of("INPUT",7L),false));
        delivery.advance(attempt);assertEquals("review",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());return id;
    }
    void reviewResult(String id,String head,boolean degraded,boolean blocker) {
        var execution=store.load(id).progress().execution();var repo=sources.get(source).orElseThrow().repository();
        review=ReviewIds.reviewId(repo,execution.pullRequest().number());
        var run=runs.find(execution.runId()).orElseThrow();
        assertTrue(reviews.claimRepository(review,repository,repo,901));
        reviews.registerHeader(review,repo,901,"TEST proposed work","TEST author","900123",run.branch(),run.baseBranch(),head,
                execution.pullRequest().url(),"github","reviewing",1);
        reviews.setPrState(review,"OPEN");
        var findings=blocker?List.of(new Finding("TEST-file",null,Severity.BLOCKER,"TEST unresolved blocker",null)):List.<Finding>of();
        var result=new ReviewResult(findings,"TEST measured review",ModelUsage.of("TEST-review-model",1,1),false,degraded);
        reviews.recordOutcome(review,result,ReviewProjection.STAGE_DONE);reviews.recordOpenFindings(review,result,List.of(),List.of());
        reviews.recordPosted(review,head,"TEST-summary");
        domain.on(EventEnvelope.domain(review,0,"TEST-review-completed",null,new DomainEvent.ReviewCompleted(head,"TEST-summary")));
    }
    void waiting(String id,String reason) {
        observer.advance(id);assertEquals("review",store.load(id).phase());assertEquals(reason,store.load(id).reason());
        assertNull(store.load(id).progress().execution().reviewId());
        long revision=store.history(id).size();observer.advance(id);assertEquals(revision,store.history(id).size(),"unchanged waiting evidence must not append forever");
    }
    @Test void anObservedCleanReviewReachesLandWithoutInventingMergeCapability() throws Exception {
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);observer.advance(id);
        var item=store.load(id);assertEquals("land",item.phase());assertEquals("capability_unavailable",item.workflowStatus());assertEquals("land_capability_unavailable",item.reason());
        assertEquals(review,item.progress().execution().reviewId());assertEquals(1,item.progress().calls());assertFalse(item.progress().reserved());
    }
    @Test void aMissingReviewDoesNotBecomeAPassingPhase() throws Exception {String id=delivered();waiting(id,"review_result_pending");}
    @Test void aReviewOfAnotherHeadCannotAuthorizeLand() throws Exception {
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);
        // Only the reviewed head is wrong; a second wrong posted head would hide a deleted guard.
        execute("UPDATE review_status SET commit_sha=repeat('c',40) WHERE review_id=?",review);
        assertEquals(1,count("SELECT count(*) FROM review_status WHERE review_id=? AND commit_sha=repeat('c',40) AND last_posted_commit=repeat('b',40)",review));
        waiting(id,"review_head_not_observed");
    }
    @Test void anUnpostedHeadCannotAuthorizeLand() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET last_posted_commit=NULL WHERE review_id=?",review);waiting(id,"review_head_not_observed");}
    @Test void aDegradedReviewDoesNotBecomeAPassingPhase() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),true,false);waiting(id,"review_result_pending");}
    @Test void openBlockersKeepLandWaiting() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,true);waiting(id,"review_blockers_open");}
    @Test void aClosedPullRequestCannotAuthorizeLand() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);reviews.setPrState(review,"DECLINED");waiting(id,"review_pr_not_open");}
    @Test void unreadableEvidenceCannotMasqueradeAsZeroFindings() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET findings_json='TEST-unreadable-ciphertext' WHERE review_id=?",review);waiting(id,"review_evidence_unreadable");}
    @Test void anArchivedPullRequestCannotAuthorizeLand() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET archived_at=now() WHERE review_id=?",review);waiting(id,"review_pr_not_open");}
    @Test void missingFindingEvidenceCannotAuthorizeLand() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET open_findings_json=NULL WHERE review_id=?",review);waiting(id,"review_evidence_unreadable");}
    @Test void anObjectCannotMasqueradeAsAnEmptyFindingArray() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET findings_json='{}' WHERE review_id=?",review);waiting(id,"review_evidence_unreadable");}
    @Test void malformedFindingEntriesCannotAuthorizeLand() throws Exception {String id=delivered();reviewResult(id,"b".repeat(40),false,false);execute("UPDATE review_status SET findings_json='[{}]' WHERE review_id=?",review);waiting(id,"review_evidence_unreadable");}
    @Test void unknownReconciliationVerdictsCannotAuthorizeLand() throws Exception {
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);
        execute("UPDATE review_status SET reconciliation_json='[{\"loc\":\"TEST-file\",\"msg\":\"TEST-finding\",\"sev\":\"nit\",\"status\":\"TEST-UNKNOWN\"}]' WHERE review_id=?",review);
        waiting(id,"review_evidence_unreadable");
    }
    @Test void resolvedReconciliationEvidenceCanReachLand() throws Exception {
        String id=delivered();reviewResult(id,"b".repeat(40),false,false);
        execute("UPDATE review_status SET reconciliation_json='[{\"loc\":\"TEST-file\",\"msg\":\"TEST-finding\",\"sev\":\"nit\",\"status\":\"RESOLVED\"}]' WHERE review_id=?",review);
        observer.advance(id);assertEquals("land",store.load(id).phase());assertEquals(review,store.load(id).progress().execution().reviewId());
    }
    @Override @AfterEach void cleanWork() throws Exception {
        if(review!=null){execute("DELETE FROM review_event WHERE review_id=?",review);execute("DELETE FROM review_status WHERE review_id=?",review);}
        try{super.cleanWork();}finally{if(reviewer!=null)execute("DELETE FROM scm_provider WHERE id=?",reviewer);}
    }
}
