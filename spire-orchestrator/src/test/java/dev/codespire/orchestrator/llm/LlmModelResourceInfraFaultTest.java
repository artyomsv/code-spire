package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ModelInUseException} exists so {@link LlmModelResource} can catch the deliberate in-use
 * refusal without also catching a genuine infrastructure fault and mislabelling a broken database
 * as a 409 conflict — {@link LlmModelRegistry} wraps every {@code SQLException} as a plain
 * {@link IllegalStateException}, the same broad type the in-use guards used to throw before that
 * exception was introduced. This proves the split holds: with the resource's catch narrowed to
 * {@code ModelInUseException} only, a wrapped {@code SQLException} propagates uncaught rather than
 * becoming the 409 response — the defect this test would have caught had it existed one commit
 * earlier, when the catch was still the broad {@code IllegalStateException}.
 *
 * <p>Mirrors {@link LlmModelPricerFailureTest}'s technique: instantiate the collaborators directly
 * (no CDI) and wire an unreachable {@link DataSource}, so a real {@code SQLException} fires the
 * exact catch block production code runs on a broken connection pool.
 */
class LlmModelResourceInfraFaultTest {

    @Test
    void aDatabaseFaultDuringUpdateIsNeverMistakenForAnInUseRefusal() {
        LlmModelRegistry registry = new LlmModelRegistry();
        registry.dataSource = unreachableDataSource();
        LlmModelResource resource = new LlmModelResource();
        resource.registry = registry;

        LlmModelInput anyValidInput = new LlmModelInput("openai", "TEST-INFRA-FAULT",
                "TEST infra fault", "METERED", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L),
                null, null, null, Map.of(), true);

        // assertThrows itself proves no 409 was produced: the resource's only path to a 409 is
        // throwing ClientErrorException, which is not an IllegalStateException, so if the resource
        // mistakenly caught this fault and mapped it, this assertion would fail on the wrong type
        // rather than pass.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> resource.update(UUID.randomUUID().toString(), anyValidInput));

        assertFalse(thrown instanceof ModelInUseException,
                "a wrapped SQLException must stay a plain IllegalStateException, or the resource's "
                        + "narrowed catch would mislabel a database failure as a 409 conflict: " + thrown);
    }

    /** getConnection() always fails — the same shape a fully exhausted or closed pool presents. */
    private static DataSource unreachableDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("TEST: connection pool unreachable");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("TEST: connection pool unreachable");
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
        };
    }
}
