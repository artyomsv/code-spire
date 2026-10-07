package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.ResolveGate;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The verify phase end to end in the orchestrator (M4): claim, send, result, gate, retry. Only the buses are TEST boundaries. */
@QuarkusTest
@TestSecurity(user = "TEST-verify-admin", roles = "spire-admin")
class WorkVerifyIT extends WorkPreparedFixture {
    @Inject RunResultSaga saga;
    @Inject WorkItemControl control;

    /** A prepared item whose build reached its checkpoint, so verify has started. */
    String built(String profile, int number) throws Exception {
        String id = admit(profile, number);
        register(id);
        if ("assisted".equals(profile)) {
            var gate = store.load(id).gate();
            transitions.answer(gate.id(), gate.version(), "TEST-approve-plan", true, "TEST plan", "TEST-verify-admin");
        }
        checkpoint();
        assertEquals("verify", store.load(id).phase());
        assertEquals("active", store.load(id).workflowStatus());
        return id;
    }

    void checkpoint() {
        dispatcher.drain();
        var command = heldCommands.getLast();
        saga.on(new RunResult.RunWorkReady(command.runId(), command.work(), "b".repeat(40), List.of("TEST-file"), Map.of("INPUT", 7L), 9));
    }

    void failedVerify() {
        verifier.drain();
        verifyResults.apply(failed(verifies.getLast()));
    }

    String string(String sql, String value) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    byte[] bytes(String sql, String value) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
        }
    }

    @Test void startingVerifySendsTheBoundChecksOnce() throws Exception {
        String id = built("autonomous", 61);
        verifier.drain();
        verifier.drain();
        assertEquals(1, verifies.size());
        var command = verifies.getFirst();
        assertEquals(store.load(id).progress().execution().head(), command.head());
        assertEquals(CHECKS, command.commands());
        assertEquals(60, command.timeoutSeconds());
        assertEquals("sent", string("SELECT state FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void passedCompletesVerifyAndDeliveryMayStart() throws Exception {
        String id = built("autonomous", 62);
        verifyPassed(id);
        assertTrue(store.load(id).progress().execution().verification().passed());
        assertEquals("applied", string("SELECT state FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void failedOpensAResultGateEvenInAutonomous() throws Exception {
        String id = built("autonomous", 63);
        failedVerify();
        var item = store.load(id);
        assertEquals("waiting_approval", item.workflowStatus());
        assertEquals("verify_failed", item.reason());
        assertEquals("verify", item.gate().phase());
        assertEquals("OPEN", item.gate().state());
        assertEquals(0, count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?", id));
    }

    @Test void unverifiedOpensAResultGateWithItsOwnReason() throws Exception {
        String id = built("autonomous", 64);
        verifier.drain();
        verifyResults.apply(unverified(verifies.getLast(), "tool_missing"));
        assertEquals("verify_unverified", store.load(id).reason());
        assertEquals("UNVERIFIED", string("SELECT outcome FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void retryBuildRewindsToBuildAndCountsARun() throws Exception {
        String id = built("autonomous", 65);
        failedVerify();
        var gate = store.load(id).gate();
        long runs = store.load(id).progress().runs();
        assertEquals(200, transitions.answer(gate.id(), gate.version(), "TEST-retry", true, "TEST retry", "TEST-verify-admin").status());
        var item = store.load(id);
        assertEquals("build", item.phase());
        assertEquals("active", item.workflowStatus());
        assertEquals(runs + 1, item.progress().runs());
        assertNull(item.progress().execution(), "a new build starts with no evidence of the old one");
    }

    @Test void retryStopsAtTheRunLimit() throws Exception {
        String id = built("autonomous", 66);
        for (int attempt = 0; attempt < 10 && !"stopped".equals(store.load(id).workflowStatus()); attempt++) {
            failedVerify();
            var gate = store.load(id).gate();
            transitions.answer(gate.id(), gate.version(), "TEST-retry-" + attempt, true, null, "TEST-verify-admin");
            if ("active".equals(store.load(id).workflowStatus())) checkpoint();
        }
        assertEquals("policy_cap_reached", store.load(id).reason());
        assertEquals(5, store.load(id).progress().runs());
    }

    @Test void stopNamesTheOperatorsDecision() throws Exception {
        String id = built("autonomous", 67);
        verifier.drain();
        verifyResults.apply(unverified(verifies.getLast(), "tool_missing"));
        var gate = store.load(id).gate();
        transitions.answer(gate.id(), gate.version(), "TEST-stop", false, null, "TEST-verify-admin");
        assertEquals("stopped", store.load(id).workflowStatus());
        assertEquals("verify_stopped_by_operator", store.load(id).reason());
    }

    @Test void aPullRequestReviewCannotAnswerAVerifyGate() throws Exception {
        String id = built("autonomous", 68);
        failedVerify();
        var gate = store.load(id).gate();
        var outcome = transitions.answer(new ResolveGate(gate.id(), gate.version(), "TEST-pr-review", true, null, "TEST-reviewer",
                ResolveGate.Channel.PR_REVIEW, gate.generation(), gate.artifact()));
        assertEquals(409, outcome.status());
        assertEquals("pr_review_requires_land_gate", outcome.reason());
        assertEquals("verify", store.load(id).phase());
    }

    @Test void aResultForASuspendedItemOpensNoGate() throws Exception {
        String id = built("autonomous", 69);
        verifier.drain();
        control.suspend(id, "scm", "TEST-takeover-during-verify", "900123", store.load(id).progress().execution().head());
        assertDoesNotThrow(() -> verifyResults.apply(failed(verifies.getLast())));
        var item = store.load(id);
        assertEquals("suspended", item.workflowStatus());
        assertTrue(item.gate() == null || !"OPEN".equals(item.gate().state()), "a suspended item gets no result gate");
        assertEquals("applied", string("SELECT state FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void aDuplicateResultIsAppliedOnce() throws Exception {
        String id = built("autonomous", 70);
        verifier.drain();
        var result = passed(verifies.getLast());
        verifyResults.apply(result);
        long revision = store.history(id).size();
        verifyResults.apply(result);
        assertEquals("deliver", store.load(id).phase());
        assertEquals(revision, store.history(id).size());
    }

    @Test void aResultForAnotherHeadIsRefused() throws Exception {
        String id = built("autonomous", 71);
        verifier.drain();
        var command = verifies.getLast();
        var other = new RunCommand.VerifyWork(command.runId(), command.work(), command.attemptId(), "c".repeat(40), command.commands(), command.timeoutSeconds());
        verifyResults.apply(passed(other));
        assertEquals("verify", store.load(id).phase());
        assertEquals("active", store.load(id).workflowStatus());
        assertEquals("sent", string("SELECT state FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void aPassOfOtherChecksThanTheBoundOnesIsRefused() throws Exception {
        String id = built("autonomous", 72);
        verifier.drain();
        var command = verifies.getLast();
        var otherChecks = new RunCommand.VerifyWork(command.runId(), command.work(), command.attemptId(), command.head(), List.of("TEST-other-check"), 60);
        verifyResults.apply(passed(otherChecks));
        assertEquals("verify", store.load(id).phase());
        assertEquals("phase_evidence_mismatch", string("SELECT reason FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test void theTailIsStoredEncrypted() throws Exception {
        String id = built("autonomous", 73);
        failedVerify();
        assertFalse(new String(bytes("SELECT result FROM work_verify_effect WHERE work_item_id=?", id), StandardCharsets.ISO_8859_1).contains("TEST-tail"));
    }
    @Test void aRetryStartsFromTheFailedCheckpointWithTheFailureInItsPrompt() throws Exception {
        String id = built("autonomous", 74);
        failedVerify();
        var first = verifies.getLast();
        var gate = store.load(id).gate();
        transitions.answer(gate.id(), gate.version(), "TEST-retry", true, null, "TEST-verify-admin");
        dispatcher.drain();
        var retry = heldCommands.getLast().execution();
        assertEquals(first.runId(), retry.startFromRunId());
        assertEquals(first.head(), retry.startFromHead());
        assertTrue(retry.prompt().contains("TEST-check"), retry.prompt());
        assertTrue(retry.prompt().contains("TEST-tail"), retry.prompt());
    }

    @Test void aRetryAfterAMissingCheckpointStartsFromTheBase() throws Exception {
        String id = built("autonomous", 75);
        verifier.drain();
        verifyResults.apply(unverified(verifies.getLast(), "checkpoint_missing"));
        var gate = store.load(id).gate();
        transitions.answer(gate.id(), gate.version(), "TEST-retry", true, null, "TEST-verify-admin");
        dispatcher.drain();
        var retry = heldCommands.getLast().execution();
        assertNull(retry.startFromRunId());
        assertTrue(retry.prompt().contains("could not be read"), retry.prompt());
    }

    @Test void aFirstBuildHasNoRetrySection() throws Exception {
        built("autonomous", 76);
        var first = heldCommands.getLast().execution();
        assertNull(first.startFromRunId());
        assertFalse(first.prompt().contains("did not pass verification"));
    }
}
