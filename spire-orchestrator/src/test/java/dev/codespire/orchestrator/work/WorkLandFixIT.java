package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.*;
import dev.codespire.contract.review.*;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A code refusal at land is fixed on the same pull request (M4 land, spec §7.5, A6, A7, A9): the item rewinds to
 * build, the fix runs on the item branch from the approved head, verify runs, deliver pushes with the lease and keeps
 * the pull request, the reviewer looks again, and a new land gate opens on the new head. The sweep, the decider, the
 * assembly, the dispatcher, delivery and review are production; GitHub is WireMock and the run worker is the bus.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandFixIT extends WorkLandFixture {
    static final String FIXED="f".repeat(40),PERSON="900123";
    @Inject WorkGateChannels channels;
    final List<RunCommand.PublishWorkRun> publications=new ArrayList<>();
    int reviewEvents=1;

    // The fixture's transport answers Dispatched and keeps nothing; the permit a land fix is given is what these tests read.
    @BeforeEach void capturePublications() {
        QuarkusMock.installMockForType(new WorkPublicationTransport(){
            @Override public RunLaunch.Outcome publish(RunCommand.PublishWorkRun command){publications.add(command);return new RunLaunch.Dispatched();}
        },WorkPublicationTransport.class);
    }

    long fixes(String id) {
        return store.history(id).stream().filter(event->"LAND_FIX_REQUESTED".equals(((WorkItemEvent)event.payload()).milestone())).count();
    }
    String itemBranch() {return heldCommands.getFirst().execution().branch();}
    RunCommand.ExecuteWorkRun dispatchedFix() {
        int before=heldCommands.size();dispatcher.drain();
        assertEquals(before+1,heldCommands.size(),"one fix run is dispatched");
        return heldCommands.getLast();
    }
    /** The fix reports its checkpoint and verify passes it. @return the deliver attempt */
    UUID built(String id,RunCommand.ExecuteWorkRun fix,String head) {
        saga.on(new RunResult.RunWorkReady(fix.runId(),fix.work(),head,List.of("TEST-file"),Map.of("INPUT",7L),9));
        return verifyPassed(id);
    }
    /** Deliver issues the permit, and the held publisher pushes the fix to the item branch. @return the permit */
    RunCommand.PublishWorkRun pushed(String id,RunCommand.ExecuteWorkRun fix,UUID attempt) {
        delivery.advance(attempt);
        RunCommand.PublishWorkRun permit=publications.getLast();
        saga.on(new RunResult.RunFinished(fix.runId(),"refs/heads/"+fix.execution().branch(),List.of("TEST-file"),List.of(),Map.of("INPUT",7L),false));
        delivery.advance(attempt);
        return permit;
    }
    /** The reviewer's result on {@code head}, recorded the way the review pipeline records it. */
    void reviewedAt(String id,String head,boolean blocker) {
        WorkExecution execution=store.load(id).progress().execution();RepoRef repo=sources.get(source).orElseThrow().repository();
        review=ReviewIds.reviewId(repo,execution.pullRequest().number());
        FactoryRunProjection.RunView run=runs.find(execution.runId()).orElseThrow();
        reviews.claimRepository(review,repository,repo,901);
        reviews.registerHeader(review,repo,901,"TEST proposed work","TEST author","900123",run.branch(),run.baseBranch(),head,
                execution.pullRequest().url(),"github","reviewing",1);
        reviews.setPrState(review,"OPEN");
        List<Finding> findings=blocker?List.of(new Finding("TEST-file",null,Severity.BLOCKER,"TEST unresolved blocker",null)):List.of();
        ReviewResult result=new ReviewResult(findings,"TEST measured review",ModelUsage.of("TEST-review-model",1,1),false,false);
        reviews.recordOutcome(review,result,ReviewProjection.STAGE_DONE);reviews.recordOpenFindings(review,result,List.of(),List.of());
        reviews.recordPosted(review,head,"TEST-summary");
        domain.on(EventEnvelope.domain(review,reviewEvents++,"TEST-review-completed",null,new DomainEvent.ReviewCompleted(head,"TEST-summary")));
    }
    /** One whole fix of an approved land the sweep just turned into a fix, up to the land gate on {@code head}. */
    RunCommand.PublishWorkRun fixedTo(String id,String head) {
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        RunCommand.PublishWorkRun permit=pushed(id,fix,built(id,fix,head));
        assertEquals("review",store.load(id).phase(),store.load(id).reason());
        reviewedAt(id,head,false);observer.advance(id);
        WorkItemEvent item=store.load(id);
        assertEquals("land",item.phase());assertEquals("waiting_approval",item.workflowStatus(),item.reason());
        assertEquals(head,item.gate().artifact(),"the new land gate is on the fixed head");
        return permit;
    }
    String latestEffect(String id,String column) throws SQLException {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(
                "SELECT "+column+" FROM work_land_effect WHERE work_item_id=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){assertTrue(rs.next(),"no land effect");return rs.getString(1);}
        }
    }
    void handedAtLand(String id,String reason) {
        WorkItemEvent item=store.load(id);
        assertEquals("land",item.phase());assertEquals("awaiting_input",item.workflowStatus());assertEquals(reason,item.reason());
    }
    void fixAttempts(int attempts) {
        landSettings.save(repository,new LandSettings.Input(0,"merge","comment",attempts),"TEST-land-admin");
    }
    long pullRequestsOpened() {return forge.countRequestsMatching(postRequestedFor(urlPathEqualTo("/repos/"+scope+"/pulls")).build()).getCount();}
    IntegrationEvent.RepositoryActivity onGitHub(String id,String kind,String sender,String mergedBy) {
        WorkItemEvent item=store.load(id);
        return new IntegrationEvent.RepositoryActivity(sources.get(source).orElseThrow().repository(),kind,sender,"refs/heads/"+item.control().branch(),
                HEAD,item.progress().execution().pullRequest().number(),null,null,false).withMergedBy(mergedBy);
    }

    @Test void aFailedCheckRunsOneFixAndOpensANewLandGate() throws Exception {
        String id=approved();String branch=itemBranch();PullRequestRef pullRequest=store.load(id).progress().execution().pullRequest();
        openAt(HEAD,"unstable","failure");
        land.drain();
        assertEquals(1,fixes(id));
        WorkItemEvent fixing=store.load(id);
        assertEquals("build",fixing.phase());assertEquals("active",fixing.workflowStatus(),fixing.reason());assertNull(fixing.gate(),"no build gate");
        assertEquals(pullRequest,fixing.progress().execution().pullRequest(),"the pull request survives the rewind");
        assertEquals("fixing",latestEffect(id,"state"));
        WorkItemResource.LandView during=resource.get(id).land();
        assertTrue(during.fixInProgress());assertEquals(1,during.attempt());assertEquals(3,during.attemptsAllowed());
        assertEquals(dev.codespire.contract.port.PullRequestLander.Checks.FAILED,during.checks().state(),"the operator still reads what failed");

        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        assertEquals(branch,fix.execution().branch(),"the fix pushes to the item branch");
        assertEquals(branch,fix.execution().baseBranch(),"and clones it");
        assertEquals(HEAD,fix.execution().baseCommit(),"at the approved head");
        assertTrue(fix.execution().pushesToAnExistingBranch());assertEquals("main",fix.execution().protectedBranch());
        assertNull(fix.execution().startFromRunId(),"a land fix starts from the forge, not a retained checkpoint");
        assertTrue(fix.execution().prompt().contains("TEST-ci"),"the prompt names the failed check");
        assertEquals(branch,store.load(id).control().branch());

        RunCommand.PublishWorkRun permit=pushed(id,fix,built(id,fix,FIXED));
        assertEquals(HEAD,permit.permit().expectedRemoteHead(),"the push holds the lease on the approved head");
        assertEquals(FIXED,permit.permit().head());
        assertEquals(1,pullRequestsOpened(),"the pull request is reused, never opened again");
        WorkItemEvent reviewing=store.load(id);
        assertEquals("review",reviewing.phase());assertEquals(pullRequest,reviewing.progress().execution().pullRequest());
        assertEquals(FIXED,reviewing.progress().execution().head());

        reviewedAt(id,FIXED,false);observer.advance(id);
        WorkItemEvent gated=store.load(id);
        assertEquals("land",gated.phase());assertEquals("waiting_approval",gated.workflowStatus());assertEquals(FIXED,gated.gate().artifact());
        assertFalse(resource.get(id).land().fixInProgress(),"the new land gate ends the fix");
        assertEquals(0,merges());
    }
    @Test void aConflictAsksForABaseMerge() throws Exception {
        String id=approved();openAt(HEAD,"dirty",null);
        land.drain();
        assertEquals(1,fixes(id));assertEquals("land_conflict",latestEffect(id,"reason"));
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        assertTrue(fix.execution().prompt().contains("refs/spire-base/main"),"the agent is told to merge the fetched base");
        assertEquals("main",fix.execution().protectedBranch(),"the run unit fetches the pull request's base");
        assertEquals(BASE,fix.mergedBaseTip(),"the base tip the land effect read");
        RunCommand.PublishWorkRun permit=pushed(id,fix,built(id,fix,FIXED));
        assertEquals(BASE,permit.permit().mergedBaseTip());assertEquals(HEAD,permit.permit().expectedRemoteHead());
    }
    @Test void aFailedCheckFixCarriesNoBaseTip() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");
        land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        assertNull(fix.mergedBaseTip());
        assertNull(pushed(id,fix,built(id,fix,FIXED)).permit().mergedBaseTip());
    }
    @Test void behindSpendsNoAttempt() throws Exception {
        fixAttempts(1);
        String id=approved();openAt(HEAD,"behind",null);
        land.drain();assertEquals(1,fixes(id));
        fixedTo(id,FIXED);approve(id);
        openAt(FIXED,"behind",null);land.drain();
        assertEquals(2,fixes(id));assertEquals("build",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
    }
    @Test void zeroAttemptsHandsAFailedCheckButStillMergesBehindBase() throws Exception {
        fixAttempts(0);
        String id=approved();openAt(HEAD,"behind",null);
        land.drain();assertEquals(1,fixes(id),"behind is fixed even with no attempts");
        fixedTo(id,FIXED);approve(id);
        openAt(FIXED,"unstable","failure");land.drain();
        assertEquals(1,fixes(id));handedAtLand(id,"land_fix_attempts_exhausted");
        assertEquals("handed",latestEffect(id,"state"));
    }
    @Test void theSecondFailureWithOneAttemptIsExhausted() throws Exception {
        fixAttempts(1);
        String id=approved();openAt(HEAD,"unstable","failure");
        land.drain();assertEquals(1,fixes(id));
        fixedTo(id,FIXED);approve(id);
        openAt(FIXED,"unstable","failure");land.drain();
        assertEquals(1,fixes(id));handedAtLand(id,"land_fix_attempts_exhausted");
        assertEquals("land_fix_attempts_exhausted",latestEffect(id,"reason"));
        assertEquals("land_checks_failed",latestEffect(id,"note"),"the hand-off names what the last fix would have fixed");
    }
    @Test void anItemOutOfRunsIsHandedNotStoppedDeeper() throws Exception {
        String id=approved();
        WorkItemEvent item=store.load(id);WorkProgress p=item.progress();
        append(id,WorkItemLifecycle.state(item,item.workflowStatus(),item.reason(),"TEST_RUNS_USED",item.gate(),new WorkProgress(p.attemptId(),p.attemptPhase(),
                p.attemptState(),p.reserved(),p.startedAt(),item.policy().limits().maxRunsPerItem(),p.steps(),p.wallSeconds(),p.costMillicents(),p.calls(),p.usageUnknown(),p.execution())));
        openAt(HEAD,"unstable","failure");
        land.drain();
        assertEquals(0,fixes(id));handedAtLand(id,"land_item_limit_reached");
        assertEquals("maxRunsPerItem",latestEffect(id,"note"));
        assertEquals(1,heldCommands.size(),"no fix run");
    }
    @Test void aBlockedReviewOfAFixIsHanded() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();pushed(id,fix,built(id,fix,FIXED));
        reviewedAt(id,FIXED,true);observer.advance(id);
        handedAtLand(id,"land_fix_review_blocked");
        assertEquals(1,fixes(id),"no attempt is spent on review findings");
        assertNull(store.load(id).gate());
    }
    @Test void aBlockedReviewOutsideAFixStillWaits() throws Exception {
        String id=delivered(61);
        reviewedAt(id,HEAD,true);observer.advance(id);
        assertEquals("review",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
        assertEquals("review_blockers_open",store.load(id).reason());
    }
    @Test void aVerifyRetryInsideALandFixKeepsThePullRequestAndTheBranch() throws Exception {
        String id=approved();String branch=itemBranch();PullRequestRef pullRequest=store.load(id).progress().execution().pullRequest();
        openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        saga.on(new RunResult.RunWorkReady(fix.runId(),fix.work(),FIXED,List.of("TEST-file"),Map.of("INPUT",7L),9));
        verifier.drain();verifyResults.apply(failed(verifies.getLast()));
        WorkItemEvent failed=store.load(id);
        assertEquals("verify",failed.phase());assertEquals("verify_failed",failed.reason());
        WorkItemTransitions.Outcome retry=transitions.answer(failed.gate().id(),failed.gate().version(),"TEST-retry-"+UUID.randomUUID(),true,null,"TEST-land-admin");
        assertEquals(200,retry.status(),retry.reason());
        WorkItemEvent rebuilding=store.load(id);
        assertEquals("build",rebuilding.phase());assertEquals("active",rebuilding.workflowStatus(),rebuilding.reason());assertNull(rebuilding.gate());
        assertEquals(pullRequest,rebuilding.progress().execution().pullRequest(),"the retry keeps the pull request");
        RunCommand.ExecuteWorkRun again=dispatchedFix();
        assertEquals(branch,again.execution().branch());assertEquals(branch,again.execution().baseBranch());
        assertEquals(HEAD,again.execution().baseCommit(),"from the approved head on the forge");
        assertNull(again.execution().startFromRunId(),"never from the failed checkpoint");
        assertTrue(again.execution().pushesToAnExistingBranch());
        assertTrue(again.execution().prompt().contains("did not pass verification"),"the retry is told what verify found");
        assertTrue(again.execution().prompt().contains("TEST-ci"),"and still what the land refused");
    }
    @Test void aBuildProofWithAPullRequestOutsideAFixIsRefused() throws Exception {
        String id=admit("autonomous-land",61);register(id);dispatcher.drain();RunCommand.ExecuteWorkRun command=heldCommands.getLast();
        UUID attempt=store.load(id).progress().attemptId();
        WorkExecution proof=new WorkExecution(command.runId(),command.work(),HEAD,null,null,new PullRequestRef(901,"https://forge.example.test/TEST-pull/901",false),null,null);
        WorkItemTransitions.Outcome outcome=transitions.complete(id,new WorkItemTransitions.PhaseResult(attempt,true,0,0,0,true,proof));
        assertEquals(409,outcome.status());assertEquals("phase_evidence_mismatch",outcome.reason());
    }
    @Test void theFixRunsPushIsNotTakeover() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();dispatchedFix();
        channels.activity(repository,onGitHub(id,"push","TEST-bot",null),"TEST-fix-push");
        assertEquals("active",store.load(id).workflowStatus());assertEquals("build",store.load(id).phase());
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=? AND delivery_id='TEST-fix-push' AND outcome='recorded_machine'",id));
    }
    @Test void aHandMergeDuringAFixHoldsTheRunAndAccountsItsLateResult() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,onGitHub(id,"merged",PERSON,PERSON),"TEST-hand-merge-during-fix");
        landed(id);assertEquals(PERSON,store.load(id).progress().execution().landing().mergedBy());
        assertEquals(1,count("SELECT count(*) FROM work_run_hold_outbox WHERE run_id=?",fix.runId()),"the running fix is held");
        long calls=store.load(id).progress().calls();
        saga.on(new RunResult.RunWorkReady(fix.runId(),fix.work(),FIXED,List.of("TEST-file"),Map.of("INPUT",7L),9));
        landed(id);
        assertTrue(store.history(id).stream().anyMatch(event->"LATE_BUILD_RESULT".equals(((WorkItemEvent)event.payload()).milestone())),"its late result is accounted");
        assertEquals(calls+1,store.load(id).progress().calls());
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE run_id=? AND ready_processed",fix.runId()),"and not left for redelivery");
    }
    @Test void aLateVerifyResultIsAccountedNotFailed() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        saga.on(new RunResult.RunWorkReady(fix.runId(),fix.work(),FIXED,List.of("TEST-file"),Map.of("INPUT",7L),9));
        verifier.drain();RunCommand.VerifyWork verify=verifies.getLast();
        mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,onGitHub(id,"merged",PERSON,PERSON),"TEST-hand-merge-during-verify");
        landed(id);
        long wall=store.load(id).progress().wallSeconds();
        verifyResults.apply(passed(verify));
        landed(id);
        assertEquals(wall+1,store.load(id).progress().wallSeconds(),"the verify's time counts toward the item");
        assertEquals(0,count("SELECT count(*) FROM work_verify_effect WHERE attempt_id=? AND reason='item_not_active'",verify.attemptId()));
        assertEquals(1,count("SELECT count(*) FROM work_verify_effect WHERE attempt_id=? AND state='applied'",verify.attemptId()));
    }
    @Test void aCloseDuringAFixHoldsTheRun() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();
        channels.activity(repository,onGitHub(id,"closed",PERSON,null),"TEST-close-during-fix");
        WorkItemEvent item=store.load(id);
        assertEquals("stopped",item.workflowStatus());assertEquals("pr_closed_by_human",item.reason());
        assertEquals(1,count("SELECT count(*) FROM work_run_hold_outbox WHERE run_id=?",fix.runId()),"the running fix is held");
    }
    @Test void aCloseBeforeTheFixIsDispatchedRefusesItsRun() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        channels.activity(repository,onGitHub(id,"closed",PERSON,null),"TEST-close-before-dispatch");
        assertEquals("pr_closed_by_human",store.load(id).reason());
        assertEquals(1,count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND state='refused' AND reason='work_item_invalidated'",id));
        dispatcher.drain();assertEquals(1,heldCommands.size(),"no fix run after the close");
    }
    @Test void readmitIsRefusedWhileALandFixIsInProgress() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        // An assembly refusal stops the fix build before any run: the pull request stays open.
        execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);dispatcher.drain();
        assertEquals("build",store.load(id).phase());assertEquals("stopped",store.load(id).workflowStatus());
        long generation=store.load(id).generation();
        WorkItemTransitions.Outcome readmit=transitions.resume(id,store.history(id).size(),true);
        assertEquals(409,readmit.status());assertEquals("readmission_land_pending",readmit.reason());
        assertEquals(generation,store.load(id).generation());
        assertTrue(resource.get(id).land().fixInProgress());
    }
    @Test void aFailedFixRunIsHandedNotFailed() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        RunCommand.ExecuteWorkRun fix=dispatchedFix();long calls=store.load(id).progress().calls();
        saga.on(new RunResult.RunFailed(fix.runId(),"AGENT_FAILED","TEST-agent failure",false,Map.of("INPUT",1L)));
        handedAtLand(id,"land_fix_run_failed");
        assertEquals(calls+1,store.load(id).progress().calls(),"the failed run's usage is accounted");
        assertEquals(409,transitions.resume(id,store.history(id).size(),true).status(),"readmit would build beside the open pull request");
    }
    @Test void aFailedBuildOutsideAFixStillFails() throws Exception {
        String id=admit("autonomous-land",61);register(id);dispatcher.drain();
        saga.on(new RunResult.RunFailed(heldCommands.getLast().runId(),"AGENT_FAILED","TEST-agent failure",false,Map.of("INPUT",1L)));
        assertEquals("build",store.load(id).phase());assertEquals("failed",store.load(id).workflowStatus());assertEquals("phase_failed",store.load(id).reason());
    }
    @Test void aLandFixRunGoesThroughTheBuildsSpendChecks() throws Exception {
        String id=approved();openAt(HEAD,"unstable","failure");land.drain();
        execute("UPDATE llm_model SET pricing_mode='METERED' WHERE id=?",modelId);dispatcher.drain();
        assertEquals(1,heldCommands.size(),"no fix run without a price for every token type");
        assertEquals("model_pricing_incomplete:INPUT,CACHED_INPUT,CACHE_WRITE,OUTPUT,REASONING",store.load(id).reason());
    }
}
