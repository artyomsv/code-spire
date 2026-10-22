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
 * A code refusal requests a land fix instead; WorkLandFixIT proves that loop.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandingIT extends WorkLandFixture {

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
        assertEquals("unknown",store.load(id).progress().execution().landing().method(),"a person's merge does not report its method");
    }
    // Review of slice 9: the method is kept on the row when the merge is claimed, so a lost answer recovered after the
    // repository's setting changed records the method that was sent, not the one saved now.
    @Test void anUncertainMergeRecordsTheMethodItSent() throws Exception {
        factoryIdentity(FACTORY_ID);
        landSettings.save(repository,new LandSettings.Input(landSettings.get(repository).revision(),"squash","comment",3),"TEST-land-admin");
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();
        assertEquals("uncertain",effect(id,"state"));assertEquals("squash",effect(id,"method"));
        landSettings.save(repository,new LandSettings.Input(landSettings.get(repository).revision(),"rebase","comment",3),"TEST-land-admin");
        mergedBy(FACTORY_ID);land.drain();
        landed(id);assertEquals("squash",store.load(id).progress().execution().landing().method());
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
}
