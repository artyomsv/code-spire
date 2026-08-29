package dev.codespire.worker.adapters;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rung 2's index (ADR-026 §7). Deterministic throughout — unlike rung 1's value, which needed an
 * operator to judge findings, "does this table return the files that mention a symbol" is a fact.
 */
@QuarkusTest
class PostgresSymbolIndexTest {

    private static final String REPO = "TEST-WS/TEST-REPO";
    private static final String COMMIT = "TESTSHA0000000000000000000000000000000a";

    @Inject
    PostgresSymbolIndex index;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        deleteTestRows();
    }

    @Test
    void findsTheFilesThatReferenceASymbol() {
        index.record(REPO, "src/A.java", COMMIT, List.of("Alpha"), List.of("Pricer"));
        index.record(REPO, "src/B.java", COMMIT, List.of("Beta"), List.of("Pricer"));
        index.record(REPO, "src/C.java", COMMIT, List.of("Pricer"), List.of("Unrelated"));

        List<String> callers = index.callersOf(REPO, "Pricer");

        assertEquals(2, callers.size(), callers.toString());
        assertTrue(callers.contains("src/A.java"));
        assertTrue(callers.contains("src/B.java"));
        assertFalse(callers.contains("src/C.java"), "the file that DEFINES it is not one of its callers");
    }

    /**
     * The reverse edge falls out of recording each file's outbound references — there is no separate
     * structure, which is the point of the (repo, symbol, path, role) key.
     */
    @Test
    void definitionsAndReferencesAreSeparateRolesOfTheSameSymbol() {
        index.record(REPO, "src/Pricer.java", COMMIT, List.of("Pricer"), List.of());
        index.record(REPO, "src/Billing.java", COMMIT, List.of("Billing"), List.of("Pricer"));

        assertEquals(List.of("src/Billing.java"), index.callersOf(REPO, "Pricer"));
    }

    @Test
    void isScopedToOneRepository() {
        // The same identifier exists in every codebase. Without the repo key, one deployment's
        // reviews would cite another repository's files.
        index.record(REPO, "src/A.java", COMMIT, List.of(), List.of("Shared"));
        index.record("TEST-WS/TEST-OTHER", "src/Z.java", COMMIT, List.of(), List.of("Shared"));

        assertEquals(List.of("src/A.java"), index.callersOf(REPO, "Shared"));
    }

    @Test
    void reRecordingTheSameFileDoesNotDuplicateIt() {
        index.record(REPO, "src/A.java", COMMIT, List.of(), List.of("Pricer"));
        index.record(REPO, "src/A.java", "TESTSHA0000000000000000000000000000000b", List.of(), List.of("Pricer"));

        assertEquals(List.of("src/A.java"), index.callersOf(REPO, "Pricer"));
        assertEquals("TESTSHA0000000000000000000000000000000b", lastSeenCommit("src/A.java"),
                "the row is refreshed to the commit it was last observed at");
    }

    /**
     * {@code last_seen_commit} is diagnostic and pruning metadata only (§7.2). A read that filtered
     * on it would have reintroduced the invalidation pass the design exists to avoid — so a row
     * recorded at an old commit must still be offered as a candidate.
     */
    @Test
    void aRowFromAnOlderCommitIsStillACandidate() {
        index.record(REPO, "src/Old.java", "TESTSHA000000000000000000000000000000old", List.of(), List.of("Pricer"));

        assertEquals(List.of("src/Old.java"), index.callersOf(REPO, "Pricer"),
                "candidates are confirmed at citation time, not filtered by the commit they were seen at");
    }

    @Test
    void returnsNothingRatherThanFailingForAnUnknownSymbol() {
        assertEquals(List.of(), index.callersOf(REPO, "NeverSeen"));
        assertEquals(List.of(), index.callersOf(REPO, null));
        assertEquals(List.of(), index.callersOf(null, "Pricer"));
    }

    @Test
    void aBlankRecordIsIgnoredRatherThanStored() {
        index.record(REPO, "src/A.java", COMMIT, List.of("", "  "), List.of("Real"));

        assertEquals(List.of(), index.callersOf(REPO, ""));
        assertEquals(List.of("src/A.java"), index.callersOf(REPO, "Real"));
    }

    /**
     * The caller must FETCH each candidate to confirm it, so an unbounded read becomes unbounded
     * network work and an unbounded prompt. A very common identifier is exactly where that bites.
     */
    @Test
    void capsHowManyCandidatesOneSymbolCanReturn() {
        for (int i = 0; i < PostgresSymbolIndex.MAX_CANDIDATES + 10; i++) {
            index.record(REPO, "src/File" + i + ".java", COMMIT, List.of(), List.of("Everywhere"));
        }

        assertEquals(PostgresSymbolIndex.MAX_CANDIDATES, index.callersOf(REPO, "Everywhere").size());
    }

    @Test
    void prunesRowsOlderThanTheRetentionWindowAndKeepsTheRest() {
        index.record(REPO, "src/Fresh.java", COMMIT, List.of(), List.of("Pricer"));
        index.record(REPO, "src/Stale.java", COMMIT, List.of(), List.of("Pricer"));
        age("src/Stale.java");

        index.prune();

        // Pruning costs recall, never correctness: the stale caller simply goes unmentioned.
        assertEquals(List.of("src/Fresh.java"), index.callersOf(REPO, "Pricer"));
    }

    /**
     * Each role gets its own half of the per-file budget.
     *
     * <p>Spending it DEFINES-first starved the only role {@code callersOf} reads, so a file
     * declaring more symbols than the budget wrote no reference rows at all and could never be a
     * caller candidate — and the largest files are the likeliest callers. Nothing asserted the cap
     * before, so the starvation was invisible.
     */
    @Test
    void neitherRoleCanConsumeTheOtherHalfOfThePerFileBudget() {
        List<String> manyDefines = new ArrayList<>();
        for (int i = 0; i < PostgresSymbolIndex.MAX_ROWS_PER_FILE * 2; i++) {
            manyDefines.add("Define" + i);
        }
        index.record(REPO, "src/Huge.java", COMMIT, manyDefines, List.of("StillRecorded"));

        assertEquals(List.of("src/Huge.java"), index.callersOf(REPO, "StillRecorded"),
                "a reference must survive however many symbols the file declares");
        assertTrue(rowCount("src/Huge.java", "DEFINES") <= PostgresSymbolIndex.MAX_ROWS_PER_ROLE);
    }

    /**
     * One oversized identifier used to fail the batch, and the catch discarded every row for the
     * file — silently, and easy to trigger from generated code.
     */
    @Test
    void oneOverlongSymbolDoesNotDiscardTheWholeFile() {
        index.record(REPO, "src/Mixed.java", COMMIT, List.of(), List.of("a".repeat(300), "Usable"));

        assertEquals(List.of("src/Mixed.java"), index.callersOf(REPO, "Usable"));
    }

    private int rowCount(String path, String role) {
        String sql = "SELECT count(*) FROM code_symbol WHERE repo = ? AND path = ? AND role = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REPO);
            ps.setString(2, path);
            ps.setString(3, role);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count rows", e);
        }
    }
    private String lastSeenCommit(String path) {
        String sql = "SELECT last_seen_commit FROM code_symbol WHERE repo = ? AND path = ? LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REPO);
            ps.setString(2, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read last_seen_commit", e);
        }
    }

    /** Ages one path past the retention window. Bound, not concatenated — test SQL is still SQL. */
    private void age(String path) {
        String sql = "UPDATE code_symbol SET last_seen_at = now() - interval '400 days'"
                + " WHERE repo = ? AND path = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REPO);
            ps.setString(2, path);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not age " + path, e);
        }
    }

    private void deleteTestRows() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM code_symbol WHERE repo LIKE ?")) {
            ps.setString(1, "TEST-%");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cleanup failed", e);
        }
    }
}
