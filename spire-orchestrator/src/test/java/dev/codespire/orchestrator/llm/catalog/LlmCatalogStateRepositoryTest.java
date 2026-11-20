package dev.codespire.orchestrator.llm.catalog;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one catalog row on the real database. Each test leaves the row as V117 created it. */
@QuarkusTest
class LlmCatalogStateRepositoryTest {

    private static final String DOCUMENT = "providers: []\nmodels: []\n";
    private static final String SHA = "TEST-sha-" + "0".repeat(55);

    @Inject
    LlmCatalogStateRepository repository;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @AfterEach
    void resetTheRow() throws SQLException {
        resetCatalogRow(dataSource);
    }

    /** Back to the migration's state: every column null. Shared with the other tests that write the row. */
    static void resetCatalogRow(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    UPDATE llm_catalog_state SET document = NULL, sha256 = NULL, etag = NULL, source = NULL,
                           url = NULL, loaded_at = NULL, last_checked_at = NULL, last_success_at = NULL,
                           last_error = NULL, last_change = NULL WHERE id = 1""");
        }
    }

    private LlmCatalogStateRepository.StoredDocument stored() {
        return new LlmCatalogStateRepository.StoredDocument(DOCUMENT, SHA, "\"TEST-etag\"", "remote",
                "https://example.invalid/catalog.yaml", "{\"added\":[\"TEST-a\"]}");
    }

    @Test
    void storeDocumentWritesEveryColumnAndNotifies() throws SQLException {
        Instant before = Instant.now().minusSeconds(5);
        try (Connection listener = DriverManager.getConnection(url, username, password)) {
            listener.setAutoCommit(true);
            try (Statement s = listener.createStatement()) {
                s.execute("LISTEN " + LlmCatalogStateRepository.CHANNEL);
            }

            repository.storeDocument(stored());

            PGNotification[] notices = listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(notices, "no notice on " + LlmCatalogStateRepository.CHANNEL);
            assertEquals(SHA, notices[0].getParameter());
        }
        LlmCatalogState state = repository.read();
        assertEquals(DOCUMENT, state.document());
        assertEquals(SHA, state.sha256());
        assertEquals("\"TEST-etag\"", state.etag());
        assertEquals("remote", state.source());
        assertEquals("https://example.invalid/catalog.yaml", state.url());
        assertTrue(state.loadedAt().isAfter(before));
        assertTrue(state.lastCheckedAt().isAfter(before));
        assertTrue(state.lastSuccessAt().isAfter(before));
        assertNull(state.lastError());
        assertTrue(state.lastChangeJson().contains("TEST-a"), state.lastChangeJson());
    }

    @Test
    void recordFailureKeepsTheDocument() {
        repository.storeDocument(stored());
        Instant success = repository.read().lastSuccessAt();

        repository.recordFailure("TEST-503");

        LlmCatalogState state = repository.read();
        assertEquals(DOCUMENT, state.document());
        assertEquals(SHA, state.sha256());
        assertEquals("TEST-503", state.lastError());
        assertEquals(success, state.lastSuccessAt());
        assertTrue(!state.lastCheckedAt().isBefore(success));
    }

    @Test
    void recordUnchangedMovesLastSuccess() throws InterruptedException {
        repository.storeDocument(stored());
        repository.recordFailure("TEST-503");
        Instant firstSuccess = repository.read().lastSuccessAt();
        Thread.sleep(20);

        repository.recordUnchanged("\"TEST-etag-2\"");

        LlmCatalogState state = repository.read();
        assertTrue(state.lastSuccessAt().isAfter(firstSuccess));
        assertEquals("\"TEST-etag-2\"", state.etag());
        assertEquals(DOCUMENT, state.document());
        assertNull(state.lastError());
    }

    @Test
    void anOverlongEtagIsNotKept() {
        repository.storeDocument(stored());

        repository.recordUnchanged("x".repeat(257));

        assertNull(repository.read().etag());
    }
}
