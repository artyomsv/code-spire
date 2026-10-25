package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
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
        assertEquals(List.of(new RunResult.BlockedChange("Jenkinsfile", "MODIFIED")),
                outcome.blocked(),
                "the kind the publisher reported must survive the parse — it is the whole reason"
                        + " the publisher emits an object rather than a bare path");
    }

    @Test
    void aFailureAfterTheLastPushOutranksIt() {
        // Continuous checkpointing: a push, then a later push that FAILED. Reporting the earlier
        // ref would tell the operator the run delivered when its final state never reached the
        // remote -- the same lie a refusal after a push would tell, and handled the same way.
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[{"path":"a.md","kind":"ADDED"}]}""");
        outcome.accept("""
                {"event":"failed","cause":"PUSH_FAILED","detail":"REJECTED_NONFASTFORWARD"}""");

        assertTrue(outcome.pushedRef().isEmpty(), "a stale ref must not be reported as the outcome");
        assertEquals("PUSH_FAILED", outcome.failureCause().orElseThrow());
    }

    /** The reverse order is a real success: the failure was a transient one the next push cured. */
    @Test
    void aNonTerminalFailureAfterAPushLeavesThePushStanding() {
        // The publisher skips an unreadable bundle and keeps reading; the earlier checkpoints are on
        // the branch. Treating that cause like a forge refusal discarded four real pushes.
        outcome.accept("{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/x\",\"changed\":[{\"path\":\"a\",\"kind\":\"ADDED\"}]}");
        outcome.accept("{\"event\":\"failed\",\"cause\":\"BUNDLE_UNREADABLE\",\"detail\":\"too large\"}");

        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of("refs/heads/spire/x"), outcome.pushedRef());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of("BUNDLE_UNREADABLE"), outcome.failureCause());
    }

    @Test
    void pathsBeyondTheCapAreCountedNotListed() {
        // A run touching sixty thousand files produced a result larger than a Kafka record; the
        // lists are bounded and the remainder is counted so nothing is lost silently.
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < PublisherOutcome.MAX_PATHS + 7; i++) {
            changed.append(i == 0 ? "" : ",").append("{\"path\":\"f").append(i).append("\",\"kind\":\"ADDED\"}");
        }
        outcome.accept("{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/x\",\"changed\":[" + changed + "]}");

        org.junit.jupiter.api.Assertions.assertEquals(PublisherOutcome.MAX_PATHS, outcome.changedPaths().size());
        org.junit.jupiter.api.Assertions.assertEquals(7, outcome.omittedPaths());
    }

    @Test
    void aPushAfterAFailureIsStillAPush() {
        outcome.accept("""
                {"event":"failed","cause":"BUNDLE_UNREADABLE","detail":"short read"}""");
        outcome.accept("""
                {"event":"pushed","ref":"refs/heads/a","changed":[{"path":"a.md","kind":"ADDED"}]}""");

        assertEquals("refs/heads/a", outcome.pushedRef().orElseThrow());
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
    void theArtifactLineIsKept() {
        outcome.accept("{\"event\":\"artifact\",\"text\":\"TEST-spec\\n## Outcome\"}");

        assertEquals("TEST-spec\n## Outcome", outcome.artifact().orElseThrow());
        assertTrue(outcome.handoffRefusals().isEmpty());
    }

    /** Truncation would turn an over-long specification into a shorter, valid-looking one. */
    @Test
    void anOverCapArtifactLineIsRefusedNotTruncated() {
        outcome.accept("{\"event\":\"artifact\",\"text\":\"" + "T".repeat(32 * 1024 + 1) + "\"}");

        assertTrue(outcome.artifact().isEmpty(), "an over-cap artifact is dropped, never truncated");
        assertEquals(List.of("artifact.md:TOO_LARGE"), outcome.handoffRefusals());
    }

    /** The cap is in UTF-8 bytes: 16 Ki two-byte characters plus one is over it, though fewer characters. */
    @Test
    void theArtifactCapCountsBytesNotCharacters() {
        outcome.accept("{\"event\":\"artifact\",\"text\":\"" + "ü".repeat(16 * 1024) + "\"}");
        assertTrue(outcome.artifact().isPresent(), "exactly the cap is kept");

        outcome.accept("{\"event\":\"artifact\",\"text\":\"" + "ü".repeat(16 * 1024) + "T\"}");
        assertTrue(outcome.artifact().isEmpty(), "one byte over is dropped");
    }

    @Test
    void theLastArtifactLineWins() {
        outcome.accept("{\"event\":\"artifact\",\"text\":\"TEST-first\"}");
        outcome.accept("{\"event\":\"artifact\",\"text\":\"TEST-second\"}");

        assertEquals("TEST-second", outcome.artifact().orElseThrow());
    }

    @Test
    void theStepSummaryIsKeptAndCapped() {
        outcome.accept("{\"event\":\"step_summary\",\"json\":\"{\\\"decisions\\\":[]}\"}");
        assertEquals("{\"decisions\":[]}", outcome.stepSummary().orElseThrow());

        outcome.accept("{\"event\":\"step_summary\",\"json\":\"" + "T".repeat(8 * 1024 + 1) + "\"}");
        assertTrue(outcome.stepSummary().isEmpty(), "an over-cap summary is dropped, never truncated");
        assertEquals(List.of("step-summary.json:TOO_LARGE"), outcome.handoffRefusals());
    }

    @Test
    void aRepositoryChangeIsCounted() {
        outcome.accept("{\"event\":\"repository_changed\",\"bundles\":2}");

        assertEquals(2, outcome.repositoryChanged());
        assertTrue(outcome.pushedRef().isEmpty(), "a repository change is not a push");
        assertTrue(outcome.failureCause().isEmpty(), "a repository change is a warning, not a failure");
    }

    @Test
    void aRefusedHandoffFileIsListed() {
        outcome.accept("{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"SYMLINK\"}");

        assertEquals(List.of("artifact.md:SYMLINK"), outcome.handoffRefusals());
        assertTrue(outcome.artifact().isEmpty());
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
