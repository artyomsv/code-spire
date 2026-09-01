package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim fails CLOSED on a database fault.
 *
 * <p>{@link RunClaimStoreTest} proves the claim against a real Postgres; nothing forced the fault
 * path. That path is the one that matters most: a claim that answered {@code true} when it could
 * not read its table would turn one outage into an unbounded number of duplicate agent runs, each
 * paid for.
 */
class RunClaimStoreFailClosedTest {

    /** A DataSource whose every connection attempt is the outage. */
    static final class DownDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("connection refused");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @Test
    void anUnreadableClaimTableNeverAuthorisesARun() {
        RunClaimStore store = new RunClaimStore();
        store.dataSource = new DownDataSource();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> store.claim("run::github:acme/app:finding-1:1", RunDispatcher.EXECUTE_SLOT));
        assertTrue(refused.getMessage().contains("run::github:acme/app:finding-1:1"));
        assertTrue(refused.getCause() instanceof SQLException);
    }
}
