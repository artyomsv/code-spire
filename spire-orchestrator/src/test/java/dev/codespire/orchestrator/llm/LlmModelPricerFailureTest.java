package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmModelPricer}'s catalog lookup can fail below the "model not catalogued" case: a database
 * fault mid-query. {@link LlmModelRegistryPricingTest} never exercises that branch — every case there
 * is either a query that finds nothing or one that succeeds — so this test wires the pricer to a
 * {@link DataSource} that cannot yield a connection at all and asserts the SAME UNKNOWN outcome, never
 * a coerced zero or a stray UNMETERED. Collaborators are field-injected, so the fake is set directly.
 */
class LlmModelPricerFailureTest {

    private static LlmModelPricer pricerOverA(DataSource brokenDataSource) {
        LlmModelPricer pricer = new LlmModelPricer();
        pricer.dataSource = brokenDataSource;
        pricer.rateRepository = new LlmModelRateRepository();
        return pricer;
    }

    @Test
    void aDatabaseFaultDuringPricingLookupIsUnknownNeverZero() {
        LlmModelPricer pricer = pricerOverA(unreachableDataSource());

        List<ChargeLine> lines = pricer.priceCall("TEST-ANY-MODEL",
                ModelUsage.of("TEST-ANY-MODEL", 1_000, 500));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null));
    }

    @Test
    void aDatabaseFaultDuringPricingLookupMakesAModelUnpriceable() {
        LlmModelPricer pricer = pricerOverA(unreachableDataSource());

        assertFalse(pricer.isPriceable("TEST-ANY-MODEL"));
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
