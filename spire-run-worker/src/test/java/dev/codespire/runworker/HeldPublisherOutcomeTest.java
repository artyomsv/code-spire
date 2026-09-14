package dev.codespire.runworker;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeldPublisherOutcomeTest {
    @Test void checkpointIsObservedWithoutInventingAPush() {
        var result = new PublisherOutcome();
        result.accept(checkpoint("a".repeat(40)));
        assertEquals("a".repeat(40),result.checkpointHead().orElseThrow());
        assertTrue(result.pushedRef().isEmpty());
        assertEquals(List.of("TEST-file"),result.changedPaths());
        result.accept(checkpoint("b".repeat(40)));
        assertEquals("b".repeat(40),result.checkpointHead().orElseThrow());
    }
    @Test void malformedCheckpointCannotLeaveAnEarlierHeadReady() {
        var result = new PublisherOutcome();result.accept(checkpoint("a".repeat(40)));
        result.accept(checkpoint("abc"));
        assertTrue(result.checkpointHead().isEmpty());
        assertEquals("PUBLISHER_FAILED",result.failureCause().orElseThrow());
    }
    @Test void pathRefusalOutranksTheHeldCheckpoint() {
        var result = new PublisherOutcome();result.accept(checkpoint("a".repeat(40)));
        result.accept("{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"TEST-file\",\"kind\":\"MODIFIED\"}]}");
        assertTrue(result.refused());assertTrue(result.checkpointHead().isEmpty());
    }
    @Test void terminalPublisherFailureOutranksTheHeldCheckpoint() {
        var result = new PublisherOutcome();result.accept(checkpoint("a".repeat(40)));
        result.accept("{\"event\":\"failed\",\"cause\":\"PUBLISHER_FAILED\",\"detail\":\"TEST fault\"}");
        assertTrue(result.checkpointHead().isEmpty());
    }
    @Test void unreadableBundleKeepsTheLastObservedCheckpoint() {
        var result = new PublisherOutcome();result.accept(checkpoint("a".repeat(40)));
        result.accept("{\"event\":\"failed\",\"cause\":\"BUNDLE_UNREADABLE\",\"detail\":\"TEST fault\"}");
        assertEquals(java.util.Optional.of("a".repeat(40)),result.checkpointHead());
        assertTrue(result.pushedRef().isEmpty());
    }
    private String checkpoint(String head){return "{\"event\":\"checkpoint\",\"head\":\""+head+"\",\"changed\":[{\"path\":\"TEST-file\",\"kind\":\"ADDED\"}]}";}
}
