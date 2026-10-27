package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkReadyProtocolTest {
    static final WorkRunBinding WORK = new WorkRunBinding("TEST-work",1,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),"a".repeat(64));

    @Test void readyIsDistinctFromTerminalPublicationAndRoundTripsItsEvidence() throws Exception {
        var ready = new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of("TEST-file"),Map.of("INPUT",7L),12);
        var json = new ObjectMapper();
        String wire = json.writeValueAsString(ready);
        assertTrue(wire.contains("\"type\":\"RunWorkReady\""));
        assertEquals(ready,json.readValue(wire,RunResult.class));
        assertFalse(wire.contains("pushedRef"));
    }

    @Test void aReadyRoundTripsItsStepSummary() throws Exception {
        var summary = new StepSummary(List.of("TEST-decision"),List.of("TEST-alternative"),List.of("TEST-deviation"));
        var ready = new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of("TEST-file"),Map.of("INPUT",7L),12).withStepSummary(summary);
        var json = new ObjectMapper();
        var read = (RunResult.RunWorkReady) json.readValue(json.writeValueAsString(ready),RunResult.class);
        assertEquals(ready,read);
        assertEquals(summary,read.stepSummary());
    }

    @Test void aReadyRoundTripsItsEmptyFlag() throws Exception {
        var ready = new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of(),Map.of("INPUT",7L),12).withEmpty(true);
        var json = new ObjectMapper();
        var read = (RunResult.RunWorkReady) json.readValue(json.writeValueAsString(ready),RunResult.class);
        assertTrue(read.empty());
        assertEquals(ready,read);
    }

    @Test void aReadyWithoutASummaryReadsBackWithout() throws Exception {
        // A ready written before the summary existed has no such field; it is read as no summary, never an empty one.
        String older = "{\"type\":\"RunWorkReady\",\"runId\":\"TEST-run\",\"work\":{\"workItemId\":\"TEST-work\",\"generation\":1,"
                + "\"buildAttemptId\":\"00000000-0000-0000-0000-000000000001\",\"preparationBinding\":\"" + "a".repeat(64) + "\"},"
                + "\"head\":\"" + "b".repeat(40) + "\",\"changedPaths\":[],\"tokenUsage\":null,\"activeWallSeconds\":1}";
        assertNull(((RunResult.RunWorkReady) new ObjectMapper().readValue(older,RunResult.class)).stepSummary());
    }

    @Test void readyNeedsItsRunWorkAndCheckpoint() {
        for(String id:new String[]{null,""," "})assertThrows(IllegalArgumentException.class,
                () -> new RunResult.RunWorkReady(id,WORK,"b".repeat(40),List.of(),null,0));
        assertThrows(NullPointerException.class,() -> new RunResult.RunWorkReady("TEST-run",null,"b".repeat(40),List.of(),null,0));
        for(String head:new String[]{null,"","abc","B".repeat(40)})assertThrows(IllegalArgumentException.class,
                () -> new RunResult.RunWorkReady("TEST-run",WORK,head,List.of(),null,0));
    }

    @Test void wallTimeCannotBeNegative() {
        assertThrows(IllegalArgumentException.class,() -> new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of(),null,-1));
        assertEquals(0,new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of(),null,0).activeWallSeconds());
    }

    @Test void usageAndPathsStayImmutableAndUnknownStaysUnknown() {
        var usage = new HashMap<>(Map.of("INPUT",7L));
        var paths = new ArrayList<>(List.of("TEST-file"));
        var ready = new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),paths,usage,1);
        usage.put("INPUT",9L); paths.clear();
        assertEquals(Map.of("INPUT",7L),ready.tokenUsage());
        assertEquals(List.of("TEST-file"),ready.changedPaths());
        assertThrows(UnsupportedOperationException.class,() -> ready.tokenUsage().clear());
        assertThrows(UnsupportedOperationException.class,() -> ready.changedPaths().clear());
        assertThrows(IllegalArgumentException.class,() -> new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of(),Map.of(),1));
        assertNull(new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),List.of(),null,1).tokenUsage());
        assertThrows(NullPointerException.class,() -> new RunResult.RunWorkReady("TEST-run",WORK,"b".repeat(40),null,null,1));
    }

    @Test void runtimeBindingCoversEveryIdentityComponent() {
        // Independently calculated with Node SHA-256 over four big-endian length-prefixed UTF-8 parts.
        assertEquals("fc3261c45d2125acb9825f20ae0d13b704861d4fc0db130a9f5c025be61ffe13",WORK.publicationKey());
        for(var changed:List.of(
                new WorkRunBinding("TEST-other",1,WORK.buildAttemptId(),WORK.preparationBinding()),
                new WorkRunBinding(WORK.workItemId(),2,WORK.buildAttemptId(),WORK.preparationBinding()),
                new WorkRunBinding(WORK.workItemId(),1,UUID.fromString("00000000-0000-0000-0000-000000000002"),WORK.preparationBinding()),
                new WorkRunBinding(WORK.workItemId(),1,WORK.buildAttemptId(),"b".repeat(64)))) {
            assertNotEquals(WORK.publicationKey(),changed.publicationKey());
        }
    }
}
