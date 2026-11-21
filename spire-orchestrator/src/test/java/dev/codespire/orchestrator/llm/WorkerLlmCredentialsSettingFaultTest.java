package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A database fault reading the default provider and model throws, so the review is redelivered; it is never refused
 * {@code NO_DEFAULT_PROVIDER} or {@code NO_DEFAULT_MODEL}, which are final. Hand-built on the real registry, whose
 * database is down.
 */
class WorkerLlmCredentialsSettingFaultTest {

    @Test
    void aDefaultReadFaultThrowsAndIsNotARefusal() {
        WorkerLlmCredentials credentials = new WorkerLlmCredentials();
        credentials.registry = new LlmProviderRegistry();
        credentials.registry.dataSource = unreachable();
        credentials.models = new LlmModelRegistry() {
            @Override
            public boolean isPriceable(String model) {
                throw new AssertionError("pricing is never asked when the default could not be read");
            }
        };

        assertThrows(IllegalStateException.class, () -> credentials.resolveDefault("TEST-ws"));
    }

    /** Every connection attempt fails, as a database that is down does. */
    private static DataSource unreachable() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("TEST-unreachable");
                });
    }
}
