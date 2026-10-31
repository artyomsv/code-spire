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
 * An item at an approved land gate, reached the way the review step reaches land, and the GitHub stubs and effect-row
 * reads the land tests share. Approval, the effect row, the sweep and the GitHub lander are production; GitHub is the
 * fixture's WireMock.
 */
abstract class WorkLandFixture extends WorkPreparedFixture {
    static final String HEAD="b".repeat(40),MOVED="d".repeat(40),MERGED="e".repeat(40),FACTORY_ID="900321";
    @Inject WorkDelivery delivery;
    @Inject WorkReview observer;
    @Inject RunResultSaga saga;
    @Inject FactoryRunProjection runs;
    @Inject ReviewProjection reviews;
    @Inject DomainEventSink domain;
    @Inject WorkLandSweep land;
    @Inject WorkLandTransitions landTransitions;
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
        saga.on(new RunResult.RunFinished(command.runId(),"refs/heads/"+command.execution().branch(),List.of("TEST-file"),List.of(),Map.of("INPUT",7L),false, List.of()));
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
        // The server says the item was handed over, so the screen never keeps its own copy of the reasons (review of the branch).
        WorkItemResource.LandView view=resource.get(id).land();
        assertTrue(view.handedOver(),"the land view says the item was handed over");assertTrue(view.stoppable(),"and that Stop land ends it");
    }
    void landed(String id) {
        WorkItemEvent item=store.load(id);
        assertEquals("complete",item.phase());assertEquals("completed",item.workflowStatus());assertEquals("landed",item.reason());
    }
    long fixes(String id) {
        return store.history(id).stream().filter(event->"LAND_FIX_REQUESTED".equals(((WorkItemEvent)event.payload()).milestone())).count();
    }
    /** The ticket as GitHub now reports it, carrying only {@code labels}; the intake stub is replaced. */
    void ticketLabels(int number,String... labels) {
        var ticket=mapper.createObjectNode().put("id",50000+number).put("number",number).put("repository_url",forge.baseUrl()+"/repos/"+scope)
                .put("html_url",forge.baseUrl()+"/"+scope+"/issues/"+number).put("title","TEST-prepared-task").put("body","TEST-identical task").put("state","open");
        var array=ticket.putArray("labels");for(String label:labels)array.add(label);
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues/"+number)).willReturn(okJson(ticket.toString())));
    }
    interface Action { void run() throws Exception; }
    /** The WARN lines the land sweep logged while {@code action} ran, each as its format and its arguments. */
    List<String> landWarnings(Action action) throws Exception {
        java.util.logging.Logger logger=java.util.logging.Logger.getLogger(WorkLandSweep.class.getName());
        List<String> warnings=Collections.synchronizedList(new ArrayList<>());
        java.util.logging.Handler handler=new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if(record.getLevel().intValue()>=java.util.logging.Level.WARNING.intValue())
                    warnings.add(record.getMessage()+Arrays.toString(record.getParameters()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try { action.run(); } finally { logger.removeHandler(handler); }
        return List.copyOf(warnings);
    }
    long landings(String id) {
        return store.history(id).stream().filter(event->"WORK_ITEM_LANDED".equals(((WorkItemEvent)event.payload()).milestone())).count();
    }

    @Override @AfterEach void cleanWork() throws Exception {
        if(review!=null){execute("DELETE FROM review_event WHERE review_id=?",review);execute("DELETE FROM review_status WHERE review_id=?",review);}
        try{super.cleanWork();}finally{if(reviewer!=null)execute("DELETE FROM scm_provider WHERE id=?",reviewer);}
    }
}
