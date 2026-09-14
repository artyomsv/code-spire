package dev.codespire.orchestrator.work;

import dev.codespire.worksource.*;
import dev.codespire.contract.work.WorkItemEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real aggregate and receipts; the port supplies TEST authenticated comment pages. */
@QuarkusTest
class WorkActivityPollerIT extends WorkPolicyFixture {
    @Inject WorkActivityPoller poller;
    WorkSourceRegistry original;
    final List<WorkSourceActivity> activities=new ArrayList<>();
    boolean supported=true;
    String next;
    int reads;
    @BeforeEach void comments() throws Exception {
        configureClamp();var running=startSpecification();transitions.complete(itemId,completed(running));
        var target=io.quarkus.arc.ClientProxy.unwrap(poller);original=target.sources;
        target.sources=new WorkSourceRegistry() {
            @Override public Optional<Source> get(UUID id){return original.get(id);}
            @Override public WorkSource client(Source source){return new WorkSource() {
                @Override public boolean pollsActivities(){return supported;}
                @Override public WorkPage<WorkSourceActivity> activities(WorkIssueLocation issue,String cursor){
                    assertEquals(store.load(itemId).issue(),issue);reads++;return new WorkPage<>(List.copyOf(activities),next);
                }
                @Override public Set<Capability> capabilities(){return Set.of();}
                @Override public WorkPage<WorkIssueLocation> candidates(String cursor){throw new AssertionError("Not candidate intake");}
                @Override public Fetch fetch(WorkIssueLocation issue){throw new AssertionError("Not a ticket reader");}
                @Override public WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue,String cursor){throw new AssertionError("Not label intake");}
                @Override public String comment(WorkIssueLocation issue,String text,String key){throw new AssertionError("No tracker write");}
                @Override public void transition(WorkIssueLocation issue,String status,String key){throw new AssertionError("No tracker write");}
            };}
        };
    }
    @AfterEach void restore(){if(original!=null)io.quarkus.arc.ClientProxy.unwrap(poller).sources=original;}
    WorkSourceActivity answer(Instant occurred) {
        var gate=store.load(itemId).gate();return WorkSourceActivity.comment("TEST-polled-comment","900123",
                "/approve "+gate.id()+" "+gate.generation()+" -").at(occurred);
    }
    @Test void authenticatedPolledAnswerResolvesOnceAcrossRepeatedScans() {
        activities.add(answer(Instant.now()));poller.poll(itemId);poller.poll(itemId);
        assertEquals(1,store.history(itemId).stream().map(e->(WorkItemEvent)e.payload()).filter(e->"GATE_RESOLVED".equals(e.milestone())).count());
        assertNotEquals("suspended",store.load(itemId).workflowStatus());assertEquals(2,reads);
    }
    @Test void commentBeforeAdmissionCannotAnswerCurrentGate() {
        activities.add(answer(store.history(itemId).getFirst().occurredAt().minusSeconds(1)));poller.poll(itemId);
        assertEquals("OPEN",store.load(itemId).gate().state());assertEquals(1,reads);
    }
    @Test void undatedCommentCannotAnswerCurrentGate() {
        activities.add(answer(null));poller.poll(itemId);assertEquals("OPEN",store.load(itemId).gate().state());assertEquals(1,reads);
    }
    @Test void unsupportedPollingNeverReadsComments() {
        supported=false;activities.add(answer(Instant.now()));poller.poll(itemId);assertEquals(0,reads);assertEquals("OPEN",store.load(itemId).gate().state());
    }
    @Test void repeatedCursorFailsVisiblyInsteadOfSpinning() {
        next="TEST-same-cursor";assertThrows(WorkSourceException.class,()->poller.poll(itemId));assertEquals(2,reads);
    }
}
