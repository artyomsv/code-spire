package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.*;
import dev.codespire.contract.work.*;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.worksource.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/** The real aggregate, authority reads and PostgreSQL projections; the forge is TEST WireMock. */
@QuarkusTest
class WorkGateChannelsIT extends WorkPolicyFixture {
    @Inject WorkGateChannels channels;
    @Inject WorkItemControl control;
    @Inject WorkActivityConsumer consumer;
    @Inject dev.codespire.orchestrator.factory.FixPermissionService permissions;
    UUID reviewer;
    String recordedFactory="900001";
    static final String HEAD="b".repeat(40),OLD_HEAD="a".repeat(40),BRANCH="TEST-work-branch";

    @AfterEach void deleteReviewer() throws Exception {
        if(reviewer!=null){execute("DELETE FROM repository_account WHERE account_id=?",reviewer);execute("DELETE FROM scm_provider WHERE id=?",reviewer);}
    }
    WorkGate planGate() throws Exception {
        configureClamp();var running=startSpecification();assertEquals(200,transitions.complete(itemId,completed(running)).status());
        var item=store.load(itemId);assertEquals("waiting_approval",item.workflowStatus());return item.gate();
    }
    WorkSourceDelivery comment(String text,String actor,String key)throws Exception {
        var root=mapper.createObjectNode().put("action","created");root.set("issue",ticket());
        root.putObject("repository").put("id",10001).put("full_name",scope);
        var comment=root.putObject("comment").put("id",70001).put("body",text);comment.putObject("user").put("id",Long.parseLong(actor));
        byte[] body=mapper.writeValueAsBytes(root);var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        var signal=new dev.codespire.worksource.github.GitHubWorkIngress(SECRET,mapper).translate(Map.of("X-GitHub-Event","issue_comment",
                "X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body))),body,forge.baseUrl()).getFirst();
        return new WorkSourceDelivery(repository,source,UUID.randomUUID(),1,"github",forge.baseUrl(),sources.get(source).orElseThrow().repository(),
                key,signal);
    }
    String command(WorkGate gate) {return "/approve "+gate.id()+" "+gate.generation()+" "+(gate.artifact()==null?"-":gate.artifact());}
    long resolved() {return store.history(itemId).stream().map(e->(WorkItemEvent)e.payload()).filter(e->"GATE_RESOLVED".equals(e.milestone())).count();}

    @Test void ordinaryApprovingWordsCannotAnswerAnOtherwiseEligibleGate() throws Exception {
        var gate=planGate();assertTrue(sources.get(source).orElseThrow().allowedActors().contains("900123"));
        assertEquals("OPEN",gate.state());assertEquals(1,gate.generation());
        // Only the missing slash distinguishes this prose from the permitted, fully bound command.
        intake.accept(comment(command(gate).substring(1),"900123","TEST-prose"));
        assertEquals(0,resolved(),"Ordinary approving words must never become a gate answer");
        assertEquals("suspended",store.load(itemId).workflowStatus());
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=?",itemId));
    }
    @Test void explicitTrackerAnswerUsesTheSameGateAndDeduplicatesBeforeTakeover() throws Exception {
        var gate=planGate();var delivery=comment(command(gate),"900123","TEST-answer");
        intake.accept(delivery);assertEquals(1,resolved());
        assertEquals("tracker",transitions.gateFromHistory(itemId,gate.id()).channel());
        assertEquals("900123",transitions.gateFromHistory(itemId,gate.id()).resolver());
        long revision=store.history(itemId).size();intake.accept(delivery);
        assertEquals(revision,store.history(itemId).size());assertNotEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void trackerAnswerCannotBorrowAnotherSourcesMembership() throws Exception {
        var gate=planGate();intake.accept(comment(command(gate),"900456","TEST-outsider"));
        assertEquals(0,resolved());assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void staleGenerationCannotAnswer() throws Exception {
        var gate=planGate();intake.accept(comment(command(gate).replace(" 1 "," 2 "),"900123","TEST-old-generation"));
        assertEquals(0,resolved());assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void anotherArtifactCannotAnswer() throws Exception {
        var gate=planGate();intake.accept(comment(command(gate).replace(" -"," "+"d".repeat(64)),"900123","TEST-other-artifact"));
        assertEquals(0,resolved());assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void recordedTrackerIdentityDoesNotTakeOverAfterRotation() throws Exception {
        execute("UPDATE scm_provider SET bot_account_id='900001' WHERE id=?",account);planGate();
        assertEquals("900001",store.load(itemId).control().trackerActor());
        execute("UPDATE scm_provider SET bot_account_id='900999' WHERE id=?",account);
        intake.accept(comment("TEST status update","900001","TEST-old-tracker-bot"));
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void scmIdentityCannotImpersonateTheRecordedTrackerIdentity() throws Exception {
        planGate();var item=store.load(itemId);append(item.controlled(new WorkControl(null,null,null,"900123",null,null,null,null,"900001")));
        intake.accept(comment("TEST human comment","900123","TEST-different-namespace"));
        assertEquals("suspended",store.load(itemId).workflowStatus());
    }

    WorkGate landGate() throws Exception {
        reviewer=UUID.fromString(providers.create(new ProviderInput("TEST-reviewer-"+UUID.randomUUID(),"github",forge.baseUrl(),
                "bearer",null,"TEST-review-token","900002",true,List.of(),"TEST-renamed-reviewer",null,"REVIEWER")).id());
        var repo=repositories.get(repository).orElseThrow();
        repositories.update(repository,repo.revision(),new RepositoryInput("github",forge.baseUrl(),repo.workspace(),repo.slug(),true,reviewer,account));
        configureClamp();intake.accept(signed("900123"));var item=store.load(itemId);
        var binding=new WorkRunBinding(itemId,item.generation(),UUID.randomUUID(),"c".repeat(64));
        var execution=new WorkExecution("TEST-run",binding,HEAD,UUID.randomUUID(),new PullRequestRef(42,forge.baseUrl()+"/"+scope+"/pull/42",false),"TEST-review");
        var gate=new WorkGate(UUID.randomUUID(),1,"OPEN","land",item.generation(),store.history(itemId).size()+1,item.policyRevision(),item.authority(),HEAD,
                java.time.Instant.now(),java.time.Instant.now().plusSeconds(3600),null,null,null,null);
        append(item.decision(item.policyRevision(),item.authority(),item.policy(),"land","waiting_approval","approval_required","GATE_OPENED",gate,item.progress().withExecution(execution))
                .controlled(new WorkControl("TEST-run",binding,BRANCH,recordedFactory,"900002",null,null,null)));
        approval("APPROVED",HEAD);
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/collaborators/TEST-person/permission")).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}")));
        return gate;
    }
    void approval(String state,String head) {
        String review="{\"id\":17,\"state\":\""+state+"\",\"commit_id\":\""+head+"\",\"user\":{\"id\":900123,\"type\":\"User\"}}";
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews/17")).willReturn(okJson(review)));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1")).willReturn(okJson("["+review+"]")));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42")).willReturn(okJson("{\"number\":42,\"state\":\"open\",\"merged\":false,\"head\":{\"ref\":\""+BRANCH+"\",\"sha\":\""+HEAD+"\"},\"base\":{\"ref\":\"main\"}}")));
    }
    IntegrationEvent.RepositoryActivity event(String kind,String actor) {
        return new IntegrationEvent.RepositoryActivity(sources.get(source).orElseThrow().repository(),kind,actor,"refs/heads/"+BRANCH,HEAD,42,"17",null,false);
    }
    @Test void approvalOnOldHeadCannotResolveGateBoundToCurrentHead() throws Exception {
        var gate=landGate();approval("APPROVED",OLD_HEAD);
        assertTrue(channels.approvalAvailable(repository));assertEquals(HEAD,gate.artifact());
        assertTrue(permissions.authorizeApproval(repository,"900123").allowed(),"Measured push permission must pass, so it cannot mask the stale head guard");
        channels.activity(repository,event("approval","900123"),"TEST-stale-head");
        assertEquals("OPEN",store.load(itemId).gate().state(),"The current gate and policy match; only the older approval head refuses");assertEquals(0,resolved());
    }
    @Test void dismissedReviewCannotResolveCurrentHeadGate() throws Exception {
        landGate();approval("DISMISSED",HEAD);
        // The list still contains its older approval observation. Only the named reread refuses.
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1"))
                .willReturn(okJson("[{\"id\":17,\"state\":\"APPROVED\",\"user\":{\"id\":900123}}]")));
        channels.activity(repository,event("approval","900123"),"TEST-dismissed");
        assertEquals("OPEN",store.load(itemId).gate().state());assertEquals(0,resolved());
    }
    @Test void laterChangesRequestSupersedesNamedApproval() throws Exception {
        landGate();
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1"))
                .willReturn(okJson("[{\"id\":17,\"state\":\"APPROVED\",\"user\":{\"id\":900123}},{\"id\":18,\"state\":\"CHANGES_REQUESTED\",\"user\":{\"id\":900123}}]")));
        channels.activity(repository,event("approval","900123"),"TEST-superseded-review");
        assertEquals("OPEN",store.load(itemId).gate().state());assertEquals(0,resolved());
    }
    @Test void latestListedStateMustStillBeApprovedEvenWhenTheNamedReadIsOlder()throws Exception {
        landGate();forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1"))
                .willReturn(okJson("[{\"id\":17,\"state\":\"DISMISSED\",\"user\":{\"id\":900123}}]")));
        channels.activity(repository,event("approval","900123"),"TEST-latest-dismissed");assertEquals(0,resolved());
    }
    @Test void anotherLatestApprovalDoesNotAuthorizeTheNamedOlderReview()throws Exception {
        landGate();forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews?per_page=100&page=1"))
                .willReturn(okJson("[{\"id\":18,\"state\":\"APPROVED\",\"user\":{\"id\":900123}}]")));
        channels.activity(repository,event("approval","900123"),"TEST-later-approval");assertEquals(0,resolved());
    }
    @Test void prReviewCommandCannotAnswerAPlanGate()throws Exception {
        var gate=planGate();var result=transitions.answer(new ResolveGate(gate.id(),gate.version(),"TEST-pr-on-plan",true,null,"900123",ResolveGate.Channel.PR_REVIEW,gate.generation(),gate.artifact()));
        assertEquals(409,result.status());assertEquals("pr_review_requires_land_gate",result.reason());assertEquals(0,resolved());
    }
    @Test void aggregateRechecksTrackerMembershipForARecognizedCommand()throws Exception {
        var gate=planGate();var result=transitions.answer(new ResolveGate(gate.id(),gate.version(),"TEST-outsider-command",true,null,"900456",ResolveGate.Channel.TRACKER,gate.generation(),gate.artifact()));
        assertEquals(403,result.status());assertEquals("tracker_actor_not_allowed",result.reason());assertEquals(0,resolved());
    }
    @Test void currentHumanReviewResolvesLandThroughTheAggregate() throws Exception {
        var gate=landGate();channels.activity(repository,event("approval","900123"),"TEST-current-approval");
        assertEquals("APPROVED",transitions.gateFromHistory(itemId,gate.id()).state());assertEquals("pr_review",transitions.gateFromHistory(itemId,gate.id()).channel());assertEquals(1,resolved());
    }
    @Test void approvalFromAnotherWebhookActorCannotResolve() throws Exception {
        landGate();channels.activity(repository,event("approval","900456"),"TEST-other-sender");
        assertEquals(0,resolved());assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void aBotTypedReviewCannotResolve() throws Exception {
        landGate();forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42/reviews/17"))
                .willReturn(okJson("{\"id\":17,\"state\":\"APPROVED\",\"commit_id\":\""+HEAD+"\",\"user\":{\"id\":900123,\"type\":\"Bot\"}}")));
        channels.activity(repository,event("approval","900123"),"TEST-bot-review");assertEquals(0,resolved());
    }
    @Test void recordedMachineCannotApproveEvenWhenForgeCallsItAUser() throws Exception {
        recordedFactory="900123";landGate();assertTrue(permissions.authorizeApproval(repository,"900123").allowed());
        channels.activity(repository,event("approval","900123"),"TEST-machine-user-review");assertEquals(0,resolved());assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void closedPullRequestCannotResolve() throws Exception {
        landGate();forge.stubFor(get(urlEqualTo("/repos/"+scope+"/pulls/42"))
                .willReturn(okJson("{\"number\":42,\"state\":\"closed\",\"merged\":false,\"head\":{\"ref\":\""+BRANCH+"\",\"sha\":\""+HEAD+"\"},\"base\":{\"ref\":\"main\"}}")));
        channels.activity(repository,event("approval","900123"),"TEST-closed-pr");assertEquals(0,resolved());
    }
    @Test void linkedHeadCannotAnswerGateBoundToAnEarlierArtifact() throws Exception {
        var gate=landGate();var item=store.load(itemId);
        append(item.decision(item.policyRevision(),item.authority(),item.policy(),item.phase(),item.workflowStatus(),item.reason(),"TEST-older-gate",
                new WorkGate(gate.id(),gate.version(),gate.state(),gate.phase(),gate.generation(),gate.itemRevision(),gate.policyRevision(),gate.authority(),OLD_HEAD,
                        gate.openedAt(),gate.expiresAt(),null,null,null,null),item.progress()));
        channels.activity(repository,event("approval","900123"),"TEST-gate-old-head");assertEquals(0,resolved());
    }
    @Test void recordedMachineIdSurvivesRenameAndAccountRotation() throws Exception {
        landGate();execute("UPDATE scm_provider SET bot_account_id='900999',bot_username='TEST-new-factory' WHERE id=?",account);
        var pushed=push("900001","TEST-renamed-bot");assertEquals("900001",pushed.actorId());
        channels.activity(repository,pushed,"TEST-renamed-bot");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void humanIdSuspendsAndSupersedesGateEvenWithTheBotsDisplayName() throws Exception {
        landGate();var pushed=push("900123","TEST-bot");assertEquals("900123",pushed.actorId());
        channels.activity(repository,pushed,"TEST-human-with-bot-name");
        assertEquals("suspended",store.load(itemId).workflowStatus());assertEquals("SUPERSEDED",store.load(itemId).gate().state());
        assertEquals("900123",store.load(itemId).control().operator());
    }
    @Test void unrelatedBranchCannotSuspendItem() throws Exception {
        landGate();var old=event("push","900123");
        channels.activity(repository,new IntegrationEvent.RepositoryActivity(old.repo(),"push",old.actorId(),"refs/heads/TEST-other",HEAD,0,null,null,false),"TEST-unrelated");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void unknownActorSuspendsConservatively()throws Exception {
        landGate();channels.activity(repository,event("push",null),"TEST-unknown-origin");assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void factoryActivityEnvelopeReachesTakeoverAfterWireDecoding()throws Exception {
        landGate();consumer.on(mapper.writeValueAsString(envelope(repository,UUID.randomUUID(),RepositoryEventKind.FACTORY)));
        assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void activityWithoutGatewayRegistrationCannotTakeOver()throws Exception {
        landGate();consumer.accept(envelope(repository,null,RepositoryEventKind.FACTORY));assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void reviewerEnvelopeCannotSupplyFactoryActivity()throws Exception {
        landGate();consumer.accept(envelope(repository,UUID.randomUUID(),RepositoryEventKind.REVIEWER));assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void envelopeCannotSubstituteAnotherRepositoryIdentity()throws Exception {
        landGate();consumer.accept(envelope(UUID.randomUUID(),UUID.randomUUID(),RepositoryEventKind.FACTORY));assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void disabledRepositoryDoesNotAcceptActivity()throws Exception {
        landGate();execute("UPDATE repository SET enabled=false WHERE id=?",repository);
        consumer.accept(envelope(repository,UUID.randomUUID(),RepositoryEventKind.FACTORY));assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    RepositoryDelivery envelope(UUID repo,UUID registration,RepositoryEventKind kind) {
        return new RepositoryDelivery(repo,registration,1,"github",forge.baseUrl(),kind,"TEST-envelope",event("push","900123"));
    }
    @Test void unrelatedPullRequestCannotSuspendItem() throws Exception {
        landGate();var old=event("comment","900123");
        channels.activity(repository,new IntegrationEvent.RepositoryActivity(old.repo(),old.kind(),old.actorId(),old.branch(),HEAD,43,null,"TEST-comment",false),"TEST-unrelated-pr");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void unrelatedRepositoryCannotSuspendItem() throws Exception {
        landGate();var old=event("push","900123");
        channels.activity(repository,new IntegrationEvent.RepositoryActivity(new dev.codespire.contract.scm.RepoRef("TEST-other","TEST-other"),old.kind(),old.actorId(),old.branch(),HEAD,0,null,null,false),"TEST-unrelated-repo");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void recordedReviewerDoesNotTakeOver() throws Exception {
        landGate();channels.activity(repository,event("push","900002"),"TEST-reviewer-push");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
    }
    @Test void authorizedFixTakesPrecedenceOverCommentTakeover()throws Exception {
        landGate();var pr=event("comment","900123");assertTrue(permissions.authorize(repository,"900123").allowed());
        var fix=new IntegrationEvent.RepositoryActivity(pr.repo(),pr.kind(),pr.actorId(),pr.branch(),pr.head(),pr.prId(),null,"TEST-fix-comment",true);
        channels.activity(repository,fix,"TEST-authorized-fix");channels.activity(repository,fix,"TEST-authorized-fix");
        assertEquals("waiting_approval",store.load(itemId).workflowStatus());
        assertEquals(1,count("SELECT count(*) FROM work_activity_receipt WHERE work_item_id=?",itemId));
    }
    @Test void approvalCannotResumeSuspendedItem()throws Exception {
        landGate();control.suspend(itemId,"scm","TEST-takeover","900123",HEAD);
        channels.activity(repository,event("approval","900123"),"TEST-too-late");assertEquals("suspended",store.load(itemId).workflowStatus());assertEquals(0,resolved());
    }
    @Test void explicitOperatorResumeRequiresVersionAndRefetchesHead()throws Exception {
        landGate();control.suspend(itemId,"scm","TEST-takeover","900123",HEAD);
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/branches/"+BRANCH)).willReturn(okJson("{\"name\":\""+BRANCH+"\",\"commit\":{\"sha\":\""+HEAD+"\"}}")));
        long version=store.history(itemId).size();
        assertEquals(409,control.resume(itemId,version+1,"TEST-operator","TEST-reviewed current head").status());
        assertEquals("suspended",store.load(itemId).workflowStatus());
        assertEquals(200,control.resume(itemId,version,"TEST-operator","TEST-reviewed current head").status());
        var resumed=store.load(itemId);assertEquals(2,resumed.generation());assertEquals("TEST-operator",resumed.control().operator());
        assertEquals(HEAD,resumed.control().observedHead());assertEquals("TEST-reviewed current head",resumed.control().note());
        forge.verify(getRequestedFor(urlEqualTo("/repos/"+scope+"/branches/"+BRANCH)));
    }
    @Test void retiredItemNeverResumes()throws Exception {
        landGate();control.retire(itemId,"TEST-retired","TEST-confirmed deleted");
        assertEquals("retired",store.load(itemId).workflowStatus());
        assertEquals(409,control.resume(itemId,store.history(itemId).size(),"TEST-operator","TEST-retry").status());
        assertEquals(409,transitions.resume(itemId,store.history(itemId).size(),true).status());
        assertEquals("retired",store.load(itemId).workflowStatus());
    }
    @Test void resumeRequiresOperatorNote() throws Exception {
        landGate();control.suspend(itemId,"scm","TEST-takeover","900123",HEAD);
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->control.resume(itemId,store.history(itemId).size(),"TEST-operator"," "));
        assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    @Test void resumeRequiresVerifiedOperator() throws Exception {
        landGate();control.suspend(itemId,"scm","TEST-takeover","900123",HEAD);
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->control.resume(itemId,store.history(itemId).size()," ","TEST resume"));
        assertEquals("suspended",store.load(itemId).workflowStatus());
    }
    void append(WorkItemEvent item) {
        QuarkusTransaction.requiringNew().run(()->{try(var c=dataSource.getConnection()){store.appendDecision(c,store.history(itemId),item,"TEST-fixture");}
            catch(Exception failure){throw new IllegalStateException(failure);}});
    }
    IntegrationEvent.RepositoryActivity push(String actor,String name)throws Exception {
        var root=mapper.createObjectNode().put("ref","refs/heads/"+BRANCH).put("after",HEAD);
        root.putObject("repository").put("full_name",scope);
        root.putObject("sender").put("id",Long.parseLong(actor)).put("login",name);
        root.putObject("pusher").put("name","TEST-bot");
        root.putArray("commits").addObject().putObject("author").put("name","TEST-bot");
        byte[] body=mapper.writeValueAsBytes(root);
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        var raw=new dev.codespire.contract.port.RawWebhook(Map.of("X-GitHub-Event","push","X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body))),body);
        var ingress=new dev.codespire.scm.github.GitHubIngress(SECRET,mapper,Set.of("fix"));assertTrue(ingress.verifySignature(raw));
        return (IntegrationEvent.RepositoryActivity)ingress.activity(raw).getFirst();
    }
}
