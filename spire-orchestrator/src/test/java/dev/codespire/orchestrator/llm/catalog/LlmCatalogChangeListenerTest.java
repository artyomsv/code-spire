package dev.codespire.orchestrator.llm.catalog;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener started by hand, over a hand-built catalog, follows a row written and announced with raw
 * SQL — never through storeDocument, which would swap a local catalog itself. The application's own
 * listener is off in %test; this one has its own session name so the test can wait for it.
 */
@QuarkusTest
class LlmCatalogChangeListenerTest {

    private static final String SESSION = "spire-llm-catalog-listener-test";
    private static final String DOCUMENT = """
            providers:
              - code: openai
                displayName: TEST openai
            models:
              - modelId: TEST-followed
                provider: openai
                displayName: TEST followed
                lifecycle:
                  status: ACTIVE
                api:
                  outputTokenParam: MAX_TOKENS
                  temperature: true
                pricing:
                  input: 1
                  output: 2
                  sourceUrl: https://example.invalid/pricing
                  observedAt: 2026-11-20
            """;

    @Inject
    LlmCatalogStateRepository states;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private final LlmCatalogChangeListener listener = new LlmCatalogChangeListener();

    @AfterEach
    void stopAndReset() throws SQLException {
        listener.stop();
        LlmCatalogStateRepositoryTest.resetCatalogRow(dataSource);
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", SESSION);
        return DriverManager.getConnection(url, properties);
    }

    @Test
    void aNoticeWithANewShaSwapsTheSnapshot() throws Exception {
        LlmModelCatalog catalog = CatalogTestDoubles.testCatalog();
        listener.catalog = catalog;
        listener.states = states;
        String bundledSha = catalog.snapshot().sha256();
        listener.start(this::open);
        awaitListening();

        String sha = CatalogSnapshot.sha256Of(DOCUMENT);
        writeAndAnnounce(sha);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!sha.equals(catalog.snapshot().sha256()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertEquals(sha, catalog.snapshot().sha256(), "the listener did not follow the announced document");
        assertTrue(catalog.resolve("TEST-followed").isPresent());
        assertTrue(!sha.equals(bundledSha));
    }

    private void awaitListening() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (sessions() == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertEquals(1, sessions(), "the listener never opened its session");
        // The session exists before its LISTEN commits; a short wait keeps the notice from racing it.
        Thread.sleep(200);
    }

    private int sessions() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?")) {
            ps.setString(1, SESSION);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void writeAndAnnounce(String sha) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE llm_catalog_state SET document = ?, sha256 = ?, source = 'remote' WHERE id = 1")) {
                ps.setString(1, DOCUMENT);
                ps.setString(2, sha);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_notify('llm_catalog_changed', ?)")) {
                ps.setString(1, sha);
                ps.executeQuery().close();
            }
        }
    }
}
