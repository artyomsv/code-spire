package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherOutcomeTest {

    private final PublisherOutcome outcome = new PublisherOutcome();

    @Test
    void readsAPushAndTheKindsThatCameWithIt() {
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/spire/run_1",\
                "changed":[{"path":"NEW.md","kind":"ADDED"}]}""");

        assertEquals("refs/heads/spire/run_1", outcome.pushedRef().orElseThrow());
        assertEquals(List.of("NEW.md"), outcome.changedPaths());
        assertFalse(outcome.refused());
    }

    @Test
    void theLastPushWins() {
        // Continuous checkpointing means several pushes per run, each superseding the last.
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[]}""");
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/b","changed":[]}""");

        assertEquals("refs/heads/b", outcome.pushedRef().orElseThrow());
    }

    @Test
    void aRefusalOutranksEveryPushThatPrecededIt() {
        // A run can push several times and THEN be refused. Reporting the last push as the outcome
        // would announce a successful run whose final state the gate rejected.
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[{"path":"a.md","kind":"ADDED"}]}""");
        outcome.accept("""
                {"event":"gate_refused","blocked":[{"path":"Jenkinsfile","kind":"MODIFIED"}],\
                "changed":[{"path":"Jenkinsfile","kind":"MODIFIED"}]}""");

        assertTrue(outcome.refused());
        assertTrue(outcome.pushedRef().isEmpty(), "a refused run reports no pushed ref");
        assertEquals(List.of("Jenkinsfile"), outcome.blockedPaths());
    }

    @Test
    void anUnparseableOrUnknownLineIsIgnoredRatherThanFatal() {
        // The publisher also logs plain text, and it is free to add outcomes this worker does not
        // model yet. Neither may fail a run that already pushed.
        outcome.accept("Picked up JAVA_TOOL_OPTIONS: -Xmx256m");
        outcome.accept("""
                {"event":"something_new","detail":"?"}""");
        outcome.accept("[1,2,3]");
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[]}""");

        assertEquals("refs/heads/a", outcome.pushedRef().orElseThrow());
    }

    @Test
    void aFailureIsCarriedWithItsCause() {
        outcome.accept("""
                {"event":"failed","cause":"BUNDLE_UNREADABLE","detail":"IOException: short read"}""");

        assertEquals("BUNDLE_UNREADABLE", outcome.failureCause().orElseThrow());
        assertTrue(outcome.failureDetail().contains("short read"));
    }

    @Test
    void aPathIsNeverListedTwice() {
        // A run touches the same file across several checkpoints; an operator reading the result
        // should see it once.
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[{"path":"a.md","kind":"ADDED"}]}""");
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[{"path":"a.md","kind":"MODIFIED"}]}""");

        assertEquals(List.of("a.md"), outcome.changedPaths());
    }

    @Test
    void aBarePathStringIsAcceptedAsWellAsAnObject() {
        // The publisher's shape gained kinds during review. An older publisher image emitting bare
        // strings must not silently produce an empty change list.
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":["legacy.md"]}""");

        assertEquals(List.of("legacy.md"), outcome.changedPaths());
    }
}
