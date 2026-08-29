package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two answers {@link ReviewRuns} gives when it cannot read, and why they differ.
 *
 * <p>They point in opposite directions on purpose. {@link ReviewRuns#currentRun} answers
 * {@code FIRST_RUN}, which is right for the ledger: a charge filed under run 1 is wrong but harmless,
 * and refusing to charge would lose real money. {@link ReviewRuns#roundOrUnknown} answers
 * {@code UNKNOWN_RUN}, which is right for anything that WRITES a round.
 *
 * <p>The difference is not theoretical. {@code FindingProjection.recordGenerated} replaces every row
 * for {@code (review_id, round)}, so a transient fault during round 5 that resolved to run 1 would
 * <b>delete round 1's real history</b> and file round 5's findings in its place — silently, since
 * nothing about it looks like an error. A guard against that only works if its input can actually
 * carry the failure, and with {@code currentRun} alone it never could: the projection's
 * {@code round <= 0} check was unreachable from production code.
 */
class ReviewRunsFailureTest {

    @Test
    void theLedgerReadFallsBackToFirstRunSoASpendIsNeverLost() {
        assertEquals(ReviewRuns.FIRST_RUN, brokenRuns().currentRun("review::TEST-WS/TEST-REPO#1"));
    }

    @Test
    void theRoundKeyedReadReportsUnknownRatherThanGuessingRoundOne() {
        assertEquals(ReviewRuns.UNKNOWN_RUN,
                brokenRuns().roundOrUnknown("review::TEST-WS/TEST-REPO#1"));
    }

    /**
     * The sentinel has to satisfy the projection's own guard, or the two halves of this fix would
     * pass their tests individually and still let a corrupt write through.
     */
    @Test
    void theSentinelIsWhatTheRoundKeyedWritesReject() {
        assertTrue(ReviewRuns.UNKNOWN_RUN <= 0,
                "FindingProjection skips on round <= 0, so the sentinel must fall inside that guard");
    }

    private static ReviewRuns brokenRuns() {
        ReviewRuns runs = new ReviewRuns();
        runs.dataSource = new FailingDataSource();
        return runs;
    }

    /** Every connection attempt fails — the transient-fault case, made deterministic. */
    private static final class FailingDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("TEST-INDUCED connection failure");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("TEST-INDUCED connection failure");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
