package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunEventRecord;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transcript's read side (FR-F5, ADR-034).
 *
 * <p>Bounded, TTL'd, and encrypted where it may quote source. Nothing derives state from these rows,
 * which is why they live nowhere near the event store.
 */
@QuarkusTest
class RunEventProjectionTest {

    @Inject
    RunEventProjection projection;

    @Inject
    DataSource dataSource;

    private String runId() {
        return "run::github:TEST-acme/app:subject-" + UUID.randomUUID() + ":1";
    }

    private RunEventRecord event(String runId, long seq, String kind, String text) {
        return new RunEventRecord(runId, seq, Instant.now(), kind, text, false);
    }

    @Test
    void aRunsEventsComeBackInSequenceOrder() {
        String runId = runId();

        // Deliberately out of order: the bus is keyed by run and partitions preserve order, but a
        // reader must not depend on that to render a transcript correctly.
        projection.record(event(runId, 3, "OUTPUT", "third"));
        projection.record(event(runId, 1, "THINKING", "first"));
        projection.record(event(runId, 2, "TOOL_USE", "second"));

        assertEquals(List.of("first", "second", "third"),
                projection.newestPage(runId, 100).stream().map(RunEventRecord::text).toList());
    }

    @Test
    void oneRunsTranscriptNeverContainsAnothers() {
        String mine = runId();
        String theirs = runId();
        projection.record(event(mine, 1, "OUTPUT", "mine"));
        projection.record(event(theirs, 1, "OUTPUT", "theirs"));

        assertEquals(List.of("mine"), projection.newestPage(mine, 100).stream()
                .map(RunEventRecord::text).toList());
    }

    @Test
    void aRedeliveredEventIsAbsorbedRatherThanFailingTheInsert() {
        // The first version of this test asserted only the row COUNT, which cannot fail: with a
        // plain insert the second write violates the primary key, record()'s own catch eats it, and
        // exactly one row still remains. Worse, it logged "the transcript will have a gap" on every
        // redelivery — a false alarm the test could not see.
        //
        // What discriminates is whether the statement RAN. The conflict clause absorbs a redelivery
        // and reports false; a primary-key violation reports false too, but only after failing, so
        // the first write reporting true and the second false is the pair that pins the mechanism.
        String runId = runId();
        RunEventRecord once = event(runId, 1, "OUTPUT", "only once");

        assertTrue(projection.record(once), "the first write must report that it stored the row");
        assertFalse(projection.record(once), "a redelivery must be absorbed, not stored twice");
        assertEquals(1, projection.newestPage(runId, 100).size());
    }

    @Test
    void aRowCannotBeReadBackUnderAnotherRun() {
        // The AAD binds ciphertext to its run. Without this the encryption test still passes with a
        // constant AAD, so "cannot be replayed under another run" was asserted by nothing.
        String mine = runId();
        String theirs = runId();
        projection.record(event(mine, 1, "OUTPUT", "TEST-only-for-my-run"));

        moveRowTo(mine, theirs);

        assertEquals("[this line could not be decrypted]", projection.newestPage(theirs, 100).getFirst().text(),
                "a row moved to another run must not decrypt, and must not take the page down with it");
    }

    private void moveRowTo(String from, String to) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE run_event SET run_id = ? WHERE run_id = ?")) {
            ps.setString(1, to);
            ps.setString(2, from);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not move the fixture", e);
        }
    }

    @Test
    void whatMayQuoteSourceIsEncryptedAtRest() throws SQLException {
        // A tool result quotes source, a thinking line quotes whatever the agent was reading. The
        // columns a query needs stay clear, which is the same split review_finding already makes
        // between its location columns and its message.
        String runId = runId();
        String secretish = "class Pricer { BigDecimal rate = TEST-0.42; }";

        projection.record(event(runId, 1, "TOOL_RESULT", secretish));

        assertFalse(rawPayload(runId).contains(secretish),
                "the payload is readable as plaintext in the column");
        assertEquals(secretish, projection.newestPage(runId, 100).getFirst().text(),
                "and it must still decrypt back for the operator who is allowed to read it");
    }

    @Test
    void theSweepDeletesPastTheWindowAndNothingInsideIt() {
        // The retention half of the bound. The per-run cap stops one agent flooding the bus; this
        // stops a busy deployment growing the table forever. Neither subsumes the other.
        String old = runId();
        String recent = runId();
        projection.record(event(old, 1, "OUTPUT", "aged out"));
        projection.record(event(recent, 1, "OUTPUT", "still inside the window"));
        ageEventsOf(old, Duration.ofDays(30));

        int deleted = projection.sweep(Duration.ofDays(14));

        assertTrue(deleted >= 1, "the aged row was not swept");
        assertEquals(List.of(), projection.newestPage(old, 100));
        assertEquals(1, projection.newestPage(recent, 100).size(),
                "a row inside the window must survive the same sweep that deleted one outside it");
    }

    @Test
    void aTranscriptReadIsBounded() {
        // The page an operator asks for is not "every event this run produced". The per-run cap is
        // ten thousand, and a reader that asks for all of them would hold them all in one response.
        String runId = runId();
        for (int seq = 1; seq <= 20; seq++) {
            projection.record(event(runId, seq, "OUTPUT", "line " + seq));
        }

        assertEquals(5, projection.newestPage(runId, 5).size());
    }

    private String rawPayload(String runId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT payload FROM run_event WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
                return String.join("\n", found);
            }
        }
    }

    private void ageEventsOf(String runId, Duration by) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE run_event SET recorded_at = now() - ?::interval WHERE run_id = ?")) {
            ps.setString(1, by.toDays() + " days");
            ps.setString(2, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not age the fixture", e);
        }
    }
}
