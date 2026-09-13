package dev.codespire.orchestrator.work;

import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.worksource.WorkEffectMarker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkSourceEffectsTest extends WorkFixture {
    @Inject WorkSourceEffects effects;
    @Inject dev.codespire.encryption.EncryptionService encryption;
    UUID effect;
    final String text="TEST-private-tracker-comment";
    @BeforeEach void prepareEffect() throws Exception {
        intake.accept(signed("900123"));effect=UUID.randomUUID();
        forge.stubFor(get(urlEqualTo(path+"/comments?per_page=100&page=1")).willReturn(okJson("[]")));
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{\"id\":70001}")));
        effects.enqueue(effect,itemId,1,WorkSourceEffects.Kind.COMMENT,text);
    }
    @Test void retryFindsThePreviouslyWrittenComment() {
        String scenario="TEST-write-"+effect;
        forge.stubFor(get(urlEqualTo(path+"/comments?per_page=100&page=1")).inScenario(scenario).whenScenarioStateIs(STARTED).willReturn(okJson("[]")));
        forge.stubFor(post(urlEqualTo(path+"/comments")).inScenario(scenario).whenScenarioStateIs(STARTED).willSetStateTo("TEST-written")
                .willReturn(okJson("{\"id\":70001}").withFixedDelay(11000)));
        forge.stubFor(get(urlEqualTo(path+"/comments?per_page=100&page=1")).inScenario(scenario).whenScenarioStateIs("TEST-written")
                .willReturn(okJson(mapper.createArrayNode().add(mapper.createObjectNode().put("id",70001).put("body",WorkEffectMarker.body(text,effect.toString()))).toString())));
        assertFalse(effects.dispatch(effect));assertEquals("uncertain",effects.status(effect).state());
        // A fresh service object has no memory of the HTTP attempt. The durable claim and marker suffice.
        WorkSourceEffects restarted=new WorkSourceEffects();restarted.dataSource=dataSource;restarted.store=store;restarted.sources=sources;
        restarted.policies=policies;restarted.mapper=mapper;restarted.encryption=encryption;
        assertTrue(restarted.dispatch(effect));assertEquals("sent",effects.status(effect).state());assertEquals("70001",effects.status(effect).remoteId());
        forge.verify(1,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void absentMarkerAfterUncertaintyNeverAuthorizesASecondPost() throws Exception {
        execute("UPDATE work_tracker_outbox SET state='uncertain' WHERE effect_id=?",effect);
        assertFalse(effects.dispatch(effect));assertEquals("uncertain",effects.status(effect).state());
        assertEquals("write_outcome_unknown",effects.status(effect).reason());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void successfulEffectsAreNotRepeated() {
        assertTrue(effects.dispatch(effect));forge.resetRequests();assertFalse(effects.dispatch(effect));assertEquals("sent",effects.status(effect).state());
        forge.verify(0,getRequestedFor(urlEqualTo(path)));
        forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void uncertaintyIsCommittedBeforeTheRemoteWriteCompletes() throws Exception {
        forge.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{\"id\":70001}").withFixedDelay(3000)));
        FutureTask<Boolean> task=new FutureTask<>(()->effects.dispatch(effect));Thread.ofVirtual().start(task);
        try {
            await().atMost(Duration.ofSeconds(5)).until(()->!forge.findAll(postRequestedFor(urlEqualTo(path+"/comments"))).isEmpty());
            assertEquals("uncertain",effects.status(effect).state(),"A separate connection must see the committed claim while the response is still pending");
            assertFalse(effects.dispatch(effect),"A competing sweep may inspect the outcome but must not post again");
        } finally { task.get(10,TimeUnit.SECONDS); }
        assertTrue(task.get());
        forge.verify(1,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void effectIdentityCannotBeReusedForAnotherIntent() {
        effects.enqueue(effect,itemId,1,WorkSourceEffects.Kind.COMMENT,text);
        assertThrows(IllegalArgumentException.class,()->effects.enqueue(effect,itemId,1,WorkSourceEffects.Kind.COMMENT,"TEST-different-text"));
    }
    @Test void staleItemRevisionCannotEnqueueAnEffect() {
        UUID other=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->effects.enqueue(other,itemId,2,WorkSourceEffects.Kind.COMMENT,text));assertNull(effects.status(other));
    }
    @Test void commentsAreEncryptedAndNotSentAsDomainNotifications() throws Exception {
        try(var c=dataSource.getConnection();var ps=c.prepareStatement("SELECT payload FROM work_tracker_outbox WHERE effect_id=?")) {
            ps.setObject(1,effect);try(var rs=ps.executeQuery()){assertTrue(rs.next());byte[] payload=rs.getBytes(1);
                assertFalse(new String(payload,java.nio.charset.StandardCharsets.UTF_8).contains(text));
                String decoded=assertDoesNotThrow(()->encryption.decryptString(Base64.getEncoder().encodeToString(payload),"work-tracker-effect:"+effect));
                assertTrue(decoded.contains(text));
                assertThrows(RuntimeException.class,()->encryption.decrypt(payload,"work-tracker-effect:"+UUID.randomUUID()));
            }
        }
        assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE effect_id=?",effect));
    }
    @Test void removingTheAllowedActorRefusesThePendingWrite() {
        administration.removeActor(source,"900123",sources.get(source).orElseThrow().version().source());
        assertFalse(effects.dispatch(effect));assertEquals("refused",effects.status(effect).state());assertEquals("current_policy_refused",effects.status(effect).reason());
        forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void missingAuditRefusesThePendingWrite() {
        forge.stubFor(get(urlPathEqualTo(path+"/timeline")).willReturn(aResponse().withStatus(503)));
        assertFalse(effects.dispatch(effect));assertEquals("refused",effects.status(effect).state());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void disabledAccountRefusesThePendingWrite() {
        providers.update(account,new ProviderInput("TEST-disabled","github",forge.baseUrl(),"bearer",null,null,"TEST-bot",false,List.of(),"TEST-bot",null,"FACTORY"));
        assertFalse(effects.dispatch(effect));assertEquals("refused",effects.status(effect).state());assertEquals("source_unavailable",effects.status(effect).reason());
        forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void workflowRevisionChangesRefuseTheOldIntent() throws Exception {
        execute("UPDATE work_item SET revision=revision+1 WHERE id=?",itemId);
        assertFalse(effects.dispatch(effect));assertEquals("work_item_changed",effects.status(effect).reason());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void workflowGenerationChangesRefuseTheOldIntent() throws Exception {
        execute("UPDATE work_item SET generation=generation+1 WHERE id=?",itemId);
        assertFalse(effects.dispatch(effect));assertEquals("work_item_changed",effects.status(effect).reason());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void workflowPhaseChangesRefuseTheOldIntent() throws Exception {
        execute("UPDATE work_item SET phase='plan' WHERE id=?",itemId);
        assertFalse(effects.dispatch(effect));assertEquals("work_item_changed",effects.status(effect).reason());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    FutureTask<Boolean> delayedDispatch() {
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString()).withFixedDelay(2000)));
        forge.resetRequests();FutureTask<Boolean> task=new FutureTask<>(()->effects.dispatch(effect));Thread.ofVirtual().start(task);
        await().atMost(Duration.ofSeconds(5)).until(()->!forge.findAll(getRequestedFor(urlEqualTo(path))).isEmpty());return task;
    }
    @Test void accountRotationDuringEvidenceReadDiscardsTheSplitDecision() throws Exception {
        FutureTask<Boolean> task=delayedDispatch();
        providers.update(account,new ProviderInput("TEST-rotated","github",forge.baseUrl(),"bearer",null,"TEST-rotated-token","TEST-bot",true,List.of(),"TEST-bot",null,"FACTORY"));
        assertFalse(task.get(15,TimeUnit.SECONDS));assertEquals("pending",effects.status(effect).state());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void policyEditDuringEvidenceReadDiscardsTheSplitDecision() throws Exception {
        FutureTask<Boolean> task=delayedDispatch();execute("UPDATE work_repository_policy SET revision=revision+1 WHERE repository_id=?",repository);
        assertFalse(task.get(15,TimeUnit.SECONDS));assertEquals("pending",effects.status(effect).state());forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void aWaitingWorkflowCannotTriggerATrackerTransition() {
        UUID transition=UUID.randomUUID();effects.enqueue(transition,itemId,1,WorkSourceEffects.Kind.TRANSITION,"closed");
        assertFalse(effects.dispatch(transition));assertEquals("current_policy_refused",effects.status(transition).reason());forge.verify(0,patchRequestedFor(urlEqualTo(path)));
    }
    @Test void aLoweredCeilingRefusesThePendingComment() throws Exception {
        var old=policies.get(repository).ceiling();
        policies.createVersion(new dev.codespire.contract.work.WorkPolicy.Profile(old.id(),old.name(),2,old.precedence(),Map.of()));
        policies.save(repository,new WorkPolicyRegistry.Input(1,new WorkPolicyRegistry.Pin(profile,2),Map.of(LABEL,new WorkPolicyRegistry.Pin(profile,1))));
        assertFalse(effects.dispatch(effect));assertEquals("current_policy_refused",effects.status(effect).reason());
        forge.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
}
