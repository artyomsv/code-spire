package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.*;
import dev.codespire.contract.review.*;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.pipeline.DomainEventSink;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryInput;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.*;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An approved land merges at the approved head, deletes the branch and completes the item (M4 land, spec §7.3).
 * Approval, the effect row, the sweep and the GitHub lander are production; GitHub is the fixture's WireMock.
 * Every code refusal is handed to the operator until the land fix loop exists (slice 7b).
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandingIT extends WorkPreparedFixture {
    static final String HEAD="b".repeat(40),MOVED="d".repeat(40),MERGED="e".repeat(40),FACTORY_ID="900321";
    @Inject WorkDelivery delivery;
    @Inject WorkReview observer;
    @Inject RunResultSaga saga;
    @Inject FactoryRunProjection runs;
    @Inject ReviewProjection reviews;
    @Inject DomainEventSink domain;
    @Inject WorkLandSweep land;
    @Inject LandSettings landSettings;
    @Inject WorkItemControl control;
    UUID reviewer;
    String review;
    final AtomicReference<Instant> now=new AtomicReference<>();

    @BeforeEach void reviewerAndPullRequest() {
        QuarkusMock.installMockForType(new WorkPublicationTransport(){@Override public RunLaunch.Outcome publish(RunCommand.PublishWorkRun command){return new RunLaunch.Dispatched();}},WorkPublicationTransport.class);
        reviewer=UUID.fromString(providers.create(new ProviderInput("TEST-reviewer-"+UUID.randomUUID(),"github",forge.baseUrl(),
                "bearer",null,"TEST-reviewer-secret","TEST-reviewer",true,List.of(),"TEST-reviewer",null,"REVIEWER")).id());
        var repositoryRow=repositories.get(repository).orElseThrow();var repo=sources.get(source).orElseThrow().repository();
        repositories.update(repository,repositoryRow.revision(),new RepositoryInput("github",forge.baseUrl(),repo.workspace(),repo.slug(),true,reviewer,account));
        forge.stubFor(get(urlPathEqualTo("/repos/"+scope+"/pulls")).willReturn(okJson("[]")));
        forge.stubFor(post(urlPathEqualTo("/repos/"+scope+"/pulls")).willReturn(okJson("{\"number\":901,\"html_url\":\"https://forge.example.test/TEST-pull/901\",\"draft\":false}")));
        forge.stubFor(post(urlEqualTo(pulls()+"/requested_reviewers")).willReturn(aResponse().withStatus(201).withBody("{}")));
    }
    /** The sweep's clock, for the tests that move time. WorkClock has one method, so this fake covers every path. */
    void clockAt(Instant instant) {
        now.set(instant);
        QuarkusMock.installMockForType(new WorkClock(){@Override public Instant now(){return now.get();}},WorkClock.class);
    }

    // --- an item at an approved land gate, the way the review step reaches land ---
    String delivered(int number) throws Exception {
        String id=admit("autonomous-land",number);register(id);dispatcher.drain();var command=heldCommands.getLast();
        saga.on(new RunResult.RunWorkReady(command.runId(),command.work(),HEAD,List.of("TEST-file"),Map.of("INPUT",7L),9));
        UUID attempt=verifyPassed(id);delivery.advance(attempt);
        saga.on(new RunResult.RunFinished(command.runId(),"refs/heads/"+command.execution().branch(),List.of("TEST-file"),List.of(),Map.of("INPUT",7L),false));
        delivery.advance(attempt);assertEquals("review",store.load(id).phase());return id;
    }
    void reviewResult(String id) {
        var execution=store.load(id).progress().execution();var repo=sources.get(source).orElseThrow().repository();
        review=ReviewIds.reviewId(repo,execution.pullRequest().number());
        var run=runs.find(execution.runId()).orElseThrow();
        assertTrue(reviews.claimRepository(review,repository,repo,901));
        reviews.registerHeader(review,repo,901,"TEST proposed work","TEST author","900123",run.branch(),run.baseBranch(),HEAD,
                execution.pullRequest().url(),"github","reviewing",1);
        reviews.setPrState(review,"OPEN");
        var result=new ReviewResult(List.<Finding>of(),"TEST measured review",ModelUsage.of("TEST-review-model",1,1),false,false);
        reviews.recordOutcome(review,result,ReviewProjection.STAGE_DONE);reviews.recordOpenFindings(review,result,List.of(),List.of());
        reviews.recordPosted(review,HEAD,"TEST-summary");
        domain.on(EventEnvelope.domain(review,0,"TEST-review-completed",null,new DomainEvent.ReviewCompleted(HEAD,"TEST-summary")));
    }
    String atLandGate() throws Exception {
        String id=delivered(61);reviewResult(id);observer.advance(id);
        WorkItemEvent item=store.load(id);
        assertEquals("land",item.phase());assertEquals("waiting_approval",item.workflowStatus(),item.reason());
        return id;
    }
    void approve(String id) {
        WorkGate gate=store.load(id).gate();
        var outcome=transitions.answer(gate.id(),gate.version(),"TEST-land-answer-"+UUID.randomUUID(),true,null,"TEST-land-admin");
        assertEquals(200,outcome.status(),outcome.reason());
        assertEquals("active",store.load(id).workflowStatus(),store.load(id).reason());
    }
    String approved() throws Exception {String id=atLandGate();approve(id);return id;}

    // --- GitHub ---
    String pulls() {return "/repos/"+scope+"/pulls/901";}
    String refs() {return "/repos/"+scope+"/git/refs/heads/.+";}
    String pullJson(String state,boolean merged,String head,String mergeableState,String mergedBy) {
        ObjectNode pr=mapper.createObjectNode().put("number",901).put("state",state).put("merged",merged)
                .put("mergeable",true).put("mergeable_state",mergeableState);
        if(merged){pr.put("merge_commit_sha",MERGED);pr.putObject("merged_by").put("id",Long.parseLong(mergedBy));}
        pr.putObject("head").put("sha",head).put("ref","TEST-head");pr.putObject("base").put("ref","main").put("sha",BASE);
        return pr.toString();
    }
    /** One commit status in {@code statusState}, or none at all when it is null; never any check runs. */
    void checks(String head,String statusState) {
        String statuses=statusState==null?"[]":"[{\"context\":\"TEST-ci\",\"state\":\""+statusState+"\",\"description\":\"TEST-ci said so\"}]";
        forge.stubFor(get(urlPathEqualTo("/repos/"+scope+"/commits/"+head+"/status"))
                .willReturn(okJson("{\"total_count\":"+(statusState==null?0:1)+",\"statuses\":"+statuses+"}")));
        forge.stubFor(get(urlPathEqualTo("/repos/"+scope+"/commits/"+head+"/check-runs")).willReturn(okJson("{\"total_count\":0,\"check_runs\":[]}")));
    }
    void openAt(String head,String mergeableState,String statusState) {
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson(pullJson("open",false,head,mergeableState,null))));checks(head,statusState);
    }
    void mergedBy(String by) {forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson(pullJson("closed",true,HEAD,"unknown",by))));checks(HEAD,null);}
    void merge(ResponseDefinitionBuilder answer) {forge.stubFor(put(urlPathEqualTo(pulls()+"/merge")).willReturn(answer));}
    ResponseDefinitionBuilder merged() {return okJson("{\"sha\":\""+MERGED+"\",\"merged\":true}");}
    void branchAt(String sha,int deleteStatus) {
        forge.stubFor(get(urlPathMatching("/repos/"+scope+"/git/ref/heads/.+")).willReturn(okJson("{\"object\":{\"sha\":\""+sha+"\"}}")));
        forge.stubFor(delete(urlPathMatching(refs())).willReturn(aResponse().withStatus(deleteStatus)));
    }
    int merges() {return forge.countRequestsMatching(putRequestedFor(urlPathEqualTo(pulls()+"/merge")).build()).getCount();}
    int deletes() {return forge.countRequestsMatching(deleteRequestedFor(urlPathMatching(refs())).build()).getCount();}
    int pullReads() {return forge.countRequestsMatching(getRequestedFor(urlPathEqualTo(pulls())).build()).getCount();}
    /** GitHub names a merger by numeric id, so the factory account's stable id must be one for its merge to be recognised. */
    void factoryIdentity(String providerUserId) {
        providers.update(account,new ProviderInput("TEST-work-account-"+UUID.randomUUID(),"github",forge.baseUrl(),"bearer",null,"TEST-work-token",
                providerUserId,true,List.of(),"TEST-bot",null,"FACTORY"));
    }
    void append(String id,WorkItemEvent next) {
        QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()){WorkItemTransitions.lockItem(c,id);store.appendDecision(c,store.history(id),next,"TEST-land-fixture:"+UUID.randomUUID());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }

    // --- the effect row ---
    String effect(String id,String column) throws SQLException {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT "+column+" FROM work_land_effect WHERE work_item_id=?")) {
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){assertTrue(rs.next(),"no land effect");String value=rs.getString(1);assertFalse(rs.next(),"two land effects");return value;}
        }
    }
    void handed(String id,String reason) throws SQLException {
        WorkItemEvent item=store.load(id);
        assertEquals("land",item.phase());assertEquals("awaiting_input",item.workflowStatus());assertEquals(reason,item.reason());
        assertEquals("handed",effect(id,"state"));assertEquals(reason,effect(id,"reason"));
    }
    void landed(String id) {
        WorkItemEvent item=store.load(id);
        assertEquals("complete",item.phase());assertEquals("completed",item.workflowStatus());assertEquals("landed",item.reason());
    }
    long landings(String id) {
        return store.history(id).stream().filter(event->"WORK_ITEM_LANDED".equals(((WorkItemEvent)event.payload()).milestone())).count();
    }

    @Test void anApprovedLandMergesDeletesAndCompletes() throws Exception {
        landSettings.save(repository,new LandSettings.Input(0,"squash","comment",3),"TEST-land-admin");
        String id=approved();
        openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        land.drain();
        forge.verify(1,putRequestedFor(urlPathEqualTo(pulls()+"/merge")).withRequestBody(equalToJson("{\"sha\":\""+HEAD+"\",\"merge_method\":\"squash\"}")));
        assertEquals(1,deletes());
        landed(id);
        WorkLanding landing=store.load(id).progress().execution().landing();
        assertEquals(MERGED,landing.mergeCommit());assertEquals(WorkLanding.FACTORY,landing.mergedBy());assertEquals("squash",landing.method());
        assertEquals("done",effect(id,"state"));assertNull(effect(id,"note"));
        WorkItemResource.LandView view=resource.get(id).land();
        assertEquals("done",view.state());assertEquals(Boolean.TRUE,view.branchDeleted());assertEquals("squash",view.method());assertEquals("comment",view.ticketRule());
    }
    @Test void theMergeIsPinnedToTheApprovedHead() throws Exception {
        String id=approved();openAt(MOVED,"clean",null);merge(merged());
        land.drain();
        assertEquals(0,merges());handed(id,"land_head_moved");
    }
    @Test void anUncertainMergeIsRecoveredByReadingNeverResent() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();
        assertEquals("uncertain",effect(id,"state"));assertEquals("active",store.load(id).workflowStatus());
        // GitHub finished the merge after its edge gave up; the next pass only reads, and records the factory's merge.
        mergedBy(FACTORY_ID);land.drain();
        assertEquals(1,merges());landed(id);
        assertEquals(WorkLanding.FACTORY,store.load(id).progress().execution().landing().mergedBy());
    }
    // An uncertain merge proves nothing about who merged: GitHub names the merger, and a person's merge stays the person's (A2).
    @Test void anUncertainMergeFoundMadeByAPersonRecordsThePerson() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        mergedBy("900777");land.drain();
        landed(id);assertEquals("900777",store.load(id).progress().execution().landing().mergedBy());
    }
    // The merge was pinned to the approved head, so a merge at any other head is not the factory's, whoever GitHub names.
    @Test void anUncertainMergeFoundAtAnotherHeadIsNotTheFactorys() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson(pullJson("closed",true,MOVED,"unknown",FACTORY_ID))));checks(MOVED,null);
        land.drain();
        landed(id);
        WorkLanding landing=store.load(id).progress().execution().landing();
        assertEquals(FACTORY_ID,landing.mergedBy());assertEquals(MOVED,effect(id,"merged_head"));
    }
    // GitHub finishing a lost merge must not be overtaken by a resume: the new generation would build the merged work again (§7.9).
    @Test void aResumeIsRefusedWhileAMergeMayHaveHappened() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        control.suspend(id,"github","TEST-land-push-3","900555",HEAD);
        assertEquals("suspended",store.load(id).workflowStatus());
        long revision=store.history(id).size();long generation=store.load(id).generation();
        var outcome=transitions.resumeControlled(id,revision,transitions.observe(store.load(id)),"TEST-operator","TEST-note",HEAD);
        assertEquals(409,outcome.status());assertEquals("readmission_merge_unconfirmed",outcome.reason());
        assertEquals(revision,store.history(id).size());assertEquals(generation,store.load(id).generation());
        mergedBy("900777");land.drain();
        assertEquals(1,landings(id));landed(id);
    }
    @Test void aReadmitIsRefusedWhileAMergeMayHaveHappened() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        WorkItemEvent item=store.load(id);
        append(id,WorkItemLifecycle.state(item,"stopped","gate_rejected","GATE_RESOLVED",null,item.progress().reserve(false)));
        long revision=store.history(id).size();
        given().contentType("application/json").body(new WorkItemResource.Resume(revision,true))
                .post("/api/work-items/"+id+"/resume").then().statusCode(409).body("reason",is("readmission_merge_unconfirmed"));
        assertEquals(revision,store.history(id).size());assertEquals(1,store.load(id).generation());
    }
    // A read that keeps failing waits a minute between passes, so a stuck row neither hammers GitHub nor starves newer lands.
    @Test void aPullRequestThatCannotBeReadBacksOffThenIsHanded() throws Exception {
        String id=approved();Instant start=Instant.now();clockAt(start);
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(404).withBody("{\"message\":\"TEST-Not Found\"}")));
        land.drain();
        assertEquals("land_read_failed",effect(id,"reason"));assertNotNull(effect(id,"next_check_at"));
        int reads=pullReads();
        land.drain();
        assertEquals(reads,pullReads(),"a backed-off row is not read again before its next check");
        now.set(start.plus(java.time.Duration.ofHours(6)).plusSeconds(1));land.drain();
        assertEquals(0,merges());handed(id,"land_read_failed");
    }
    @Test void aMissingFactoryAccountBacksOffThenIsHanded() throws Exception {
        String id=approved();Instant start=Instant.now();clockAt(start);openAt(HEAD,"clean",null);merge(merged());
        providers.update(account,new ProviderInput("TEST-disabled-"+UUID.randomUUID(),"github",forge.baseUrl(),"bearer",null,"TEST-work-token",
                "TEST-bot",false,List.of(),"TEST-bot",null,"FACTORY"));
        land.drain();
        assertEquals("land_account_unavailable",effect(id,"reason"));assertNotNull(effect(id,"next_check_at"));
        assertEquals("active",store.load(id).workflowStatus());
        now.set(start.plus(java.time.Duration.ofHours(6)).plusSeconds(1));land.drain();
        assertEquals(0,merges());handed(id,"land_account_unavailable");
    }
    // After the merge call the merge may have happened, so the row is never handed; it keeps reading and says why it waits.
    @Test void anUncertainMergeThatCannotBeReadIsNeverHanded() throws Exception {
        String id=approved();Instant start=Instant.now();clockAt(start);
        openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));
        land.drain();assertEquals("uncertain",effect(id,"state"));
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(502)));
        land.drain();
        assertEquals("land_read_failed",effect(id,"reason"));assertNotNull(effect(id,"next_check_at"));
        now.set(start.plus(java.time.Duration.ofHours(6)).plusSeconds(1));land.drain();
        assertEquals("uncertain",effect(id,"state"));assertEquals("land_merge_unconfirmed",effect(id,"reason"));
        assertEquals("active",store.load(id).workflowStatus());assertEquals(1,merges());
    }
    @Test void anUncertainMergeStillOpenAtTheHeadIsCheckedAgain() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        land.drain();
        assertEquals("checking",effect(id,"state"));assertEquals(1,merges(),"the read that proved it open sends nothing in the same pass");
        merge(merged());land.drain();
        assertEquals(2,merges());landed(id);
    }
    @Test void a409WithTheSameHeadChecksAgain() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(409).withBody("{\"message\":\"TEST-Base branch was modified\"}"));
        land.drain();
        assertEquals(1,merges());assertEquals("checking",effect(id,"state"));
        assertEquals("land",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
    }
    @Test void a409WithANewHeadIsHanded() throws Exception {
        String id=approved();
        forge.stubFor(get(urlPathEqualTo(pulls())).inScenario("TEST-land").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(pullJson("open",false,HEAD,"clean",null))));
        forge.stubFor(get(urlPathEqualTo(pulls())).inScenario("TEST-land").whenScenarioStateIs("TEST-pushed")
                .willReturn(okJson(pullJson("open",false,MOVED,"clean",null))));
        checks(HEAD,null);checks(MOVED,null);
        forge.stubFor(put(urlPathEqualTo(pulls()+"/merge")).inScenario("TEST-land").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("TEST-pushed")
                .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"TEST-Head branch was modified\"}")));
        land.drain();
        assertEquals(1,merges());handed(id,"land_head_moved");
    }
    @Test void a405IsReReadAndHandedWithGitHubsMessage() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);
        merge(aResponse().withStatus(405).withBody("{\"message\":\"TEST-Pull Request is not mergeable\"}"));
        land.drain();
        assertEquals(1,merges());handed(id,"land_merge_refused");
        assertEquals("TEST-Pull Request is not mergeable",resource.get(id).land().message());
    }
    @Test void pendingChecksWaitThenTimeOut() throws Exception {
        String id=approved();Instant start=Instant.now();clockAt(start);
        openAt(HEAD,"unstable","pending");merge(merged());
        land.drain();
        assertEquals("waiting_checks",effect(id,"state"));assertEquals("active",store.load(id).workflowStatus());
        now.set(start.plusSeconds(61));land.drain();
        assertEquals("waiting_checks",effect(id,"state"));
        now.set(start.plus(java.time.Duration.ofHours(6)).plusSeconds(1));land.drain();
        assertEquals(0,merges());handed(id,"land_checks_timeout");
    }
    @Test void aFailedCheckIsHandedUntilTheFixLoopExists() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");merge(merged());
        land.drain();
        assertEquals(0,merges());handed(id,"land_checks_failed");
        assertEquals(dev.codespire.contract.port.PullRequestLander.Checks.FAILED,resource.get(id).land().checks().state());
    }
    @Test void aPullRequestAlreadyMergedIsRecordedAsFound() throws Exception {
        String id=approved();mergedBy("900123");branchAt(HEAD,204);
        land.drain();
        assertEquals(0,merges());landed(id);assertEquals(1,deletes());
        WorkLanding landing=store.load(id).progress().execution().landing();
        assertEquals("900123",landing.mergedBy());assertEquals(MERGED,landing.mergeCommit());
    }
    @Test void aClosedPullRequestStopsTheItem() throws Exception {
        String id=approved();
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson(pullJson("closed",false,HEAD,"unknown",null))));checks(HEAD,null);branchAt(HEAD,204);
        land.drain();
        assertEquals("stopped",store.load(id).workflowStatus());assertEquals("pr_closed_by_human",store.load(id).reason());
        assertEquals(0,merges());assertEquals(0,deletes());assertEquals("closed",effect(id,"state"));
    }
    @Test void aBranchThatWillNotDeleteIsNotedAfterThreeTries() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,422);
        land.drain();
        landed(id);assertEquals("cleaning",effect(id,"state"));assertEquals(1,deletes());
        land.drain();land.drain();
        assertEquals("done",effect(id,"state"));assertEquals("branch_delete_failed",effect(id,"note"));assertEquals(3,deletes());
        land.drain();
        assertEquals(3,deletes());landed(id);
        WorkItemResource.LandView view=resource.get(id).land();
        assertEquals(Boolean.FALSE,view.branchDeleted());assertEquals("branch_delete_failed",view.note());
    }
    @Test void landingIsRecordedOnce() throws Exception {
        String id=approved();UUID effect=UUID.fromString(effect(id,"effect_id"));
        WorkLanding landing=new WorkLanding(store.load(id).progress().execution().pullRequest(),MERGED,"merge",WorkLanding.FACTORY,Instant.EPOCH);
        assertEquals(200,transitions.landed(id,effect,landing).status());
        var again=transitions.landed(id,effect,landing);
        assertEquals(200,again.status());assertEquals("landing_already_recorded",again.reason());
        assertEquals(1,landings(id));landed(id);
    }
    @Test void aTakeoverBeforeTheMergeCallRefusesTheEffect() throws Exception {
        String id=approved();Instant start=Instant.now();clockAt(start);
        openAt(HEAD,"unstable","pending");merge(merged());
        land.drain();assertEquals("waiting_checks",effect(id,"state"));
        control.suspend(id,"github","TEST-land-push-1","900555",MOVED);
        assertEquals("suspended",store.load(id).workflowStatus());assertEquals("refused",effect(id,"state"));
        openAt(HEAD,"clean",null);now.set(start.plusSeconds(120));land.drain();
        assertEquals(0,merges());assertEquals("refused",effect(id,"state"));assertEquals("suspended",store.load(id).workflowStatus());
    }
    @Test void aTakeoverDuringAnUncertainMergeStillRecordsTheMerge() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        control.suspend(id,"github","TEST-land-push-2","900555",HEAD);
        assertEquals("suspended",store.load(id).workflowStatus());assertEquals("uncertain",effect(id,"state"));
        mergedBy("900777");land.drain();
        assertEquals(1,merges());assertEquals(1,landings(id));landed(id);
    }
    @Test void noEffectWithoutAnApprovedLandGate() throws Exception {
        String id=approved();
        assertEquals(1,count("SELECT count(*) FROM work_land_effect WHERE work_item_id=? AND approved_head=? AND pr_number=901 AND state='pending'",id,HEAD));
        // The same PHASE_STARTED at land, but with no approved gate behind it: a history row alone is not an approval.
        String forged=admit("autonomous",62);WorkItemEvent item=store.load(forged);
        WorkExecution execution=new WorkExecution("TEST-land-run",new WorkRunBinding(forged,item.generation(),UUID.randomUUID(),"a".repeat(64)),
                HEAD,null,null,new dev.codespire.contract.scm.PullRequestRef(902,"https://forge.example.test/TEST-pull/902",false),"TEST-review");
        WorkItemEvent started=item.decision(item.policyRevision(),item.authority(),item.policy(),"land","active","phase_started","PHASE_STARTED",null,
                item.progress().start(UUID.randomUUID(),"land",Instant.EPOCH).withExecution(execution));
        QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()){WorkItemTransitions.lockItem(c,forged);store.appendDecision(c,store.history(forged),started,"TEST-land-forged:"+UUID.randomUUID());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
        assertEquals("land",store.load(forged).phase());
        assertEquals(0,count("SELECT count(*) FROM work_land_effect WHERE work_item_id=?",forged));
    }

    @Override @AfterEach void cleanWork() throws Exception {
        if(review!=null){execute("DELETE FROM review_event WHERE review_id=?",review);execute("DELETE FROM review_status WHERE review_id=?",review);}
        try{super.cleanWork();}finally{if(reviewer!=null)execute("DELETE FROM scm_provider WHERE id=?",reviewer);}
    }
}
