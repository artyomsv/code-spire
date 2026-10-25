package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the specification dispatcher reads before its claim (M4 slice 3): a ticket too long to quote whole is cut, never
 * a reason to stop, and a forge or tracker that fails to answer once is asked again later instead of stopping the item.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkSpecDispatchIT extends WorkSpecRunFixture {
    /** DispatchRequestParser's prompt limit: a longer prompt is refused at assembly. */
    private static final int DISPATCH_PROMPT_LIMIT = 64 * 1024;

    private void expireBackoff(UUID attempt) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE work_prepare_effect SET retry_after=now()-interval '1 second' WHERE attempt_id=?")) {
            ps.setObject(1, attempt);
            ps.executeUpdate();
        }
    }

    private void stubHeadFailure() {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/branches/main")).willReturn(aResponse().withStatus(502)));
    }

    @Test
    void aTicketLongerThanThePromptLimitIsDispatchedCut() throws Exception {
        String id = started("spec-approve", 101);
        stubTicket("spec-approve", 101, "TEST-long-body ".repeat(5_000));

        RunCommand.ExecuteWorkDraftRun command = dispatched();

        String prompt = command.execution().prompt();
        assertTrue(prompt.length() <= DISPATCH_PROMPT_LIMIT, "prompt length " + prompt.length());
        assertTrue(prompt.matches("(?s).*\\(truncated: \\d+ more characters\\).*"), "the cut is named inside the quote");
        assertState(store.load(id), "spec", "active", "phase_started");
    }

    @Test
    void aBranchHeadThatFailsOnceIsReadAgainLater() throws Exception {
        String id = started("spec-approve", 102);
        UUID attempt = store.load(id).progress().attemptId();
        stubHeadFailure();

        prepare.drain();
        assertTrue(draftRuns.isEmpty(), "nothing is dispatched without the base head");
        assertState(store.load(id), "spec", "active", "phase_started");
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE attempt_id=? AND state='pending' AND reason='branch_head_unconfirmed'"
                + " AND retry_after>now()", attempt));
        stubHead();
        prepare.drain();
        assertTrue(draftRuns.isEmpty(), "the backoff is honoured");

        expireBackoff(attempt);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        prepare.drain();

        assertEquals(1, draftRuns.size());
        assertEquals(HEAD, command.execution().baseCommit());
        assertEquals("sent", effectState(attempt));
    }

    @Test
    void aTrackerThatFailsOnceIsReadAgainLater() throws Exception {
        String id = started("spec-approve", 103);
        UUID attempt = store.load(id).progress().attemptId();
        forge.stubFor(get(urlEqualTo(issuePath(103))).willReturn(aResponse().withStatus(502)));

        prepare.drain();
        assertTrue(draftRuns.isEmpty(), "nothing is dispatched without the ticket");
        assertState(store.load(id), "spec", "active", "phase_started");
        assertEquals("pending", effectState(attempt));

        stubTicket("spec-approve", 103, "TEST-identical task");
        expireBackoff(attempt);
        dispatched();

        assertEquals("sent", effectState(attempt));
    }

    @Test
    void aBranchHeadThatNeverAnswersStopsTheItemByName() throws Exception {
        String id = started("spec-approve", 104);
        UUID attempt = store.load(id).progress().attemptId();
        stubHeadFailure();

        prepare.drain();
        assertState(store.load(id), "spec", "active", "phase_started");
        for (int pass = 0; pass < 10 && "active".equals(store.load(id).workflowStatus()); pass++) {
            expireBackoff(attempt);
            prepare.drain();
        }

        assertState(store.load(id), "spec", "stopped", "branch_head_unconfirmed");
        assertEquals("refused", effectState(attempt));
        assertTrue(draftRuns.isEmpty());
    }
}
