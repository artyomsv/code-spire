package dev.codespire.orchestrator.work;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.LandSettings;
import dev.codespire.worksource.WorkEffectMarker;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The ticket after land (M4 land, spec §7.7, A1). A landing enqueues a post-land comment, and under the close rule a
 * close after it, in the same transaction as WORK_ITEM_LANDED. Those rows drain after the item completed, which a
 * phase row cannot, and no row the outbox cannot handle stops the rest of the batch.
 *
 * <p>Rows are hidden from the scheduled sweep (checked_at an hour ahead) as soon as they exist, so each test drives
 * the dispatch itself; {@link WorkSourceEffects#dispatch} ignores checked_at.
 */
@QuarkusTest
class WorkPostLandEffectsTest extends WorkFixture {
    static final PullRequestRef PR=new PullRequestRef(901,"https://forge.example.test/TEST-pull/901",false);
    static final String MERGE="abcdef0"+"1".repeat(33);
    @Inject WorkSourceEffects effects;
    @Inject WorkLandTransitions transitions;
    @Inject LandSettings landSettings;
    UUID attempt;

    @BeforeEach void atLand() {
        assertDoesNotThrow(()->intake.accept(signed("900123")));
        forge.stubFor(get(urlEqualTo(path+"/comments?per_page=100&page=1")).willReturn(okJson("[]")));
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{\"id\":70001}")));
        forge.stubFor(patch(urlEqualTo(path)).willReturn(okJson("{\"id\":50001,\"state\":\"closed\"}")));
        attempt=UUID.randomUUID();
        WorkItemEvent item=store.load(itemId);
        WorkExecution execution=new WorkExecution("TEST-land-run",new WorkRunBinding(itemId,item.generation(),UUID.randomUUID(),"a".repeat(64)),
                "b".repeat(40),null,null,PR,"TEST-review");
        WorkProgress progress=item.progress().start(attempt,"land",Instant.EPOCH).withExecution(execution);
        append(item.decision(item.policyRevision(),item.authority(),item.policy(),"land","active","phase_started","PHASE_STARTED",null,progress));
    }
    void append(WorkItemEvent next) {
        QuarkusTransaction.requiringNew().run(()->{
            try(var c=dataSource.getConnection()){WorkItemTransitions.lockItem(c,itemId);store.appendDecision(c,store.history(itemId),next,"TEST-post-land:"+UUID.randomUUID());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }
    void rule(String rule) {
        landSettings.save(repository,new LandSettings.Input(landSettings.get(repository).revision(),"squash",rule,3),"TEST-land-admin");
    }
    WorkItemTransitions.Outcome land(String mergedBy) throws Exception {
        WorkItemTransitions.Outcome outcome=transitions.landed(itemId,attempt,new WorkLanding(PR,MERGE,"squash",mergedBy,Instant.EPOCH));
        executeWith("UPDATE work_tracker_outbox SET checked_at=now()+interval '1 hour' WHERE work_item_id=?",itemId);
        assertEquals(200,outcome.status(),outcome.reason());
        return outcome;
    }
    UUID comment() {return WorkSourceEffects.postLandId(itemId,store.load(itemId).generation(),WorkSourceEffects.Kind.COMMENT);}
    UUID close() {return WorkSourceEffects.postLandId(itemId,store.load(itemId).generation(),WorkSourceEffects.Kind.TRANSITION);}
    WorkSourceEffects.Status status(UUID id) {
        WorkSourceEffects.Status status=effects.status(id);
        assertNotNull(status,"no post-land row "+id);
        return status;
    }
    List<LoggedRequest> posts() {return forge.findAll(postRequestedFor(urlEqualTo(path+"/comments")));}
    int patches() {return forge.findAll(patchRequestedFor(urlEqualTo(path))).size();}
    /** The comment the factory posted, marker included, the way GitHub would list it back. */
    String postedBody() throws Exception {return mapper.readTree(posts().getLast().getBodyAsString()).path("body").asText();}
    void commentListed(String body) {
        forge.stubFor(get(urlEqualTo(path+"/comments?per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(mapper.createObjectNode().put("id",70001).put("body",body)).toString())));
    }
    void sweepUntilSent(UUID id) throws Exception {
        effects.sweep();
        await().atMost(Duration.ofSeconds(10)).until(()->"sent".equals(effects.status(id).state()));
    }

    @Test void aPostLandCommentIsSentAfterCompletion() throws Exception {
        land(WorkLanding.FACTORY);
        assertEquals("completed",store.load(itemId).workflowStatus());assertEquals("complete",store.load(itemId).phase());
        effects.dispatch(comment());
        assertEquals("sent",status(comment()).state(),status(comment()).reason());
        String body=postedBody();
        assertTrue(body.startsWith("Merged by the factory: "+PR.url()+" (abcdef0)."),body);
        assertTrue(body.contains("The ticket status is unchanged so testing can follow."),body);
        assertEquals(WorkEffectMarker.body(body.substring(0,body.indexOf("\n\n")),comment().toString()),body,"the marker is the effect id");
    }
    @Test void anUnknownPhaseRowDoesNotStallTheBatch() throws Exception {
        land(WorkLanding.FACTORY);
        UUID stale=UUID.randomUUID();
        // A phase row enqueued against the completed item carries phase 'complete', which is no policy phase.
        effects.enqueue(stale,itemId,count("SELECT revision FROM work_item WHERE id=?",itemId),WorkSourceEffects.Kind.COMMENT,"TEST-stale-phase-comment");
        executeWith("UPDATE work_tracker_outbox SET created_at=now()-interval '1 day',checked_at=NULL WHERE effect_id=?",stale);
        executeWith("UPDATE work_tracker_outbox SET checked_at=NULL WHERE effect_id=?",comment());
        sweepUntilSent(comment());
        assertEquals("refused",status(stale).state());assertEquals("unknown_phase",status(stale).reason());
    }
    @Test void aRowThatCannotBeReadDoesNotStallTheBatch() throws Exception {
        land(WorkLanding.FACTORY);
        UUID corrupt=UUID.randomUUID();
        executeWith("INSERT INTO work_tracker_outbox(effect_id,work_item_id,generation,item_revision,phase,payload,created_at) VALUES (?,?,1,1,'build',?,now()-interval '1 day')",
                corrupt,itemId,"TEST-not-ciphertext".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        executeWith("UPDATE work_tracker_outbox SET checked_at=NULL WHERE effect_id=?",comment());
        sweepUntilSent(comment());
        assertEquals("pending",status(corrupt).state());
    }
    @Test void theCloseRuleClosesAfterTheComment() throws Exception {
        rule("close");land(WorkLanding.FACTORY);
        assertEquals("pending",status(close()).state());
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(aResponse().withStatus(500)));
        effects.dispatch(comment());assertEquals("uncertain",status(comment()).state());
        assertFalse(effects.dispatch(close()),"the close waits for the comment");
        assertEquals("pending",status(close()).state());assertEquals(0,patches());
        // GitHub had kept the comment after all; the next pass finds it, and only then is the ticket closed.
        String body=postedBody();commentListed(body);
        effects.dispatch(comment());assertEquals("sent",status(comment()).state());
        assertTrue(effects.dispatch(close()));assertEquals("sent",status(close()).state());
        forge.verify(1,patchRequestedFor(urlEqualTo(path)).withRequestBody(equalToJson("{\"state\":\"closed\"}")));
        assertFalse(body.contains("unchanged"),"a ticket the rule closes is not told its status is unchanged: "+body);
    }
    @Test void aCloseWaitingForItsCommentWaitsOutTheRecheck() throws Exception {
        rule("close");land(WorkLanding.FACTORY);
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(aResponse().withStatus(500)));
        effects.dispatch(comment());assertEquals("uncertain",status(comment()).state());
        executeWith("UPDATE work_tracker_outbox SET checked_at=NULL WHERE effect_id=?",close());
        assertFalse(effects.dispatch(close()),"the close waits for the comment");
        assertEquals("pending",status(close()).state());
        // A waiter left at checked_at NULL would lead every sweep's batch and starve newer rows (A1).
        assertEquals(1,count("SELECT count(*) FROM work_tracker_outbox WHERE effect_id=? AND checked_at > now()-interval '10 seconds'",close()));
    }
    @Test void aRefusedCommentRefusesTheClose() throws Exception {
        rule("close");land(WorkLanding.FACTORY);
        executeWith("UPDATE work_tracker_outbox SET state='refused',reason='source_unavailable' WHERE effect_id=?",comment());
        assertFalse(effects.dispatch(close()));
        assertEquals("refused",status(close()).state());assertEquals("comment_not_sent",status(close()).reason());
        assertEquals(0,patches());
    }
    @Test void aMissingCommentRefusesTheClose() throws Exception {
        rule("close");land(WorkLanding.FACTORY);
        executeWith("DELETE FROM work_tracker_outbox WHERE effect_id=?",comment());
        assertFalse(effects.dispatch(close()));
        assertEquals("refused",status(close()).state());assertEquals("comment_not_sent",status(close()).reason());
        assertEquals(0,patches());
    }
    @Test void aRuleChangedToCommentDoesNotClose() throws Exception {
        rule("close");land(WorkLanding.FACTORY);
        effects.dispatch(comment());assertEquals("sent",status(comment()).state());
        rule("comment");
        assertFalse(effects.dispatch(close()));
        assertEquals("refused",status(close()).state());assertEquals("ticket_rule_changed",status(close()).reason());
        assertEquals(0,patches());
    }
    @Test @TestSecurity(user="TEST-land-admin",roles="spire-admin")
    void jiraUnderCloseCommentsAndNotesTheSkip() throws Exception {
        rule("close");
        executeWith("INSERT INTO work_land_effect(effect_id,work_item_id,generation,item_revision,pr_number,approved_head,state) VALUES (?,?,?,1,901,?,'done')",
                attempt,itemId,store.load(itemId).generation(),"b".repeat(40));
        executeWith("UPDATE work_source SET type='JIRA' WHERE id=?",source);
        try { land(WorkLanding.FACTORY); }
        finally { executeWith("UPDATE work_source SET type='GITHUB' WHERE id=?",source); }
        assertEquals("pending",status(comment()).state());
        assertNull(effects.status(close()),"Jira needs a transition id per workflow, so no close is enqueued");
        try(var c=dataSource.getConnection();var ps=c.prepareStatement("SELECT note,ticket_note FROM work_land_effect WHERE effect_id=?")) {
            ps.setObject(1,attempt);
            try(var rs=ps.executeQuery()){assertTrue(rs.next());assertNull(rs.getString(1));assertEquals("ticket_close_unsupported",rs.getString(2));}
        }
        WorkItemResource.LandView view=resource.get(itemId).land();
        assertEquals("ticket_close_unsupported",view.note());assertEquals(Boolean.TRUE,view.branchDeleted(),"the ticket note says nothing about the branch");
    }
    @Test void anUncertainPostLandCommentIsFoundNotResent() throws Exception {
        land(WorkLanding.FACTORY);
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(aResponse().withStatus(500)));
        effects.dispatch(comment());assertEquals("uncertain",status(comment()).state());
        commentListed(postedBody());
        assertTrue(effects.dispatch(comment()));assertEquals("sent",status(comment()).state());assertEquals("70001",status(comment()).remoteId());
        assertEquals(1,posts().size());
    }
    @Test void invalidationLeavesPostLandRowsAlone() throws Exception {
        land(WorkLanding.FACTORY);
        WorkItemEvent landed=store.load(itemId);
        append(WorkItemLifecycle.state(landed,"stopped","TEST-refused","WORK_ITEM_REFUSED",null,landed.progress()));
        assertEquals("pending",status(comment()).state(),status(comment()).reason());
        effects.dispatch(comment());assertEquals("sent",status(comment()).state());
    }
    @Test void aReplayedLandingDoesNotEnqueueTwice() throws Exception {
        land(WorkLanding.FACTORY);
        assertEquals("landing_already_recorded",land(WorkLanding.FACTORY).reason());
        assertEquals(1,count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='post_land'",itemId));
        assertEquals("pending",status(comment()).state());
    }
    @Test void aHumanMergeSaysWhoMerged() throws Exception {
        land("900123");
        effects.dispatch(comment());assertEquals("sent",status(comment()).state());
        assertTrue(postedBody().startsWith("Merged by TEST-person: "+PR.url()+" (abcdef0)."),postedBody());
    }
    @Test void anUnresolvedMergerIsNotPrintedAsAnId() throws Exception {
        land("900999");
        effects.dispatch(comment());assertEquals("sent",status(comment()).state());
        String body=postedBody();
        assertTrue(body.startsWith("Merged by a person: "+PR.url()),body);assertFalse(body.contains("900999"),body);
    }
    @Test void closeTransitionPerSourceType() {
        assertEquals(Optional.of("closed"),WorkSourceEffects.closeTransition(WorkSourceType.GITHUB));
        assertEquals(Optional.of("close"),WorkSourceEffects.closeTransition(WorkSourceType.GITLAB));
        assertEquals(Optional.empty(),WorkSourceEffects.closeTransition(WorkSourceType.JIRA));
    }
}
