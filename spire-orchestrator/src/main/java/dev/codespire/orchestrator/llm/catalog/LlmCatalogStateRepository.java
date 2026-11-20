package dev.codespire.orchestrator.llm.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Reads and writes the single {@code llm_catalog_state} row (V117). A stored document is announced on
 * {@code llm_catalog_changed} in the same transaction, so every instance hears of it only once it is
 * committed.
 */
@ApplicationScoped
public class LlmCatalogStateRepository {

    static final String CHANNEL = "llm_catalog_changed";
    static final String REMOTE = "remote";
    /** The etag column's width; a longer etag is not kept, so the next fetch is unconditional. */
    private static final int MAX_ETAG = 256;

    @Inject
    DataSource dataSource;

    /** What a valid, new document is stored with. */
    public record StoredDocument(String document, String sha256, String etag, String source, String url,
            String lastChangeJson) {
    }

    public LlmCatalogState read() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_catalog_state WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? toState(rs) : LlmCatalogState.EMPTY;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the LLM catalog state", e);
        }
    }

    /** Replaces the document and tells every instance, in one transaction. */
    @Transactional
    public void storeDocument(StoredDocument doc) {
        try (Connection c = dataSource.getConnection()) {
            lockRow(c);
            // last_checked_at and last_success_at describe the URL, so storing the bundled copy leaves them.
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE llm_catalog_state SET document = ?, sha256 = ?, etag = ?, source = ?, url = ?,
                           loaded_at = now(),
                           last_checked_at = CASE WHEN ? THEN now() ELSE last_checked_at END,
                           last_success_at = CASE WHEN ? THEN now() ELSE last_success_at END,
                           last_error = CASE WHEN ? THEN NULL ELSE last_error END,
                           last_change = CAST(? AS JSONB)
                     WHERE id = 1
                    """)) {
                boolean fromUrl = REMOTE.equals(doc.source());
                ps.setString(1, doc.document());
                ps.setString(2, doc.sha256());
                ps.setString(3, keptEtag(doc.etag()));
                ps.setString(4, doc.source());
                ps.setString(5, doc.url());
                ps.setBoolean(6, fromUrl);
                ps.setBoolean(7, fromUrl);
                ps.setBoolean(8, fromUrl);
                ps.setString(9, doc.lastChangeJson());
                ps.executeUpdate();
            }
            notifyChanged(c, doc.sha256());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store the LLM catalog", e);
        }
    }

    /** A 304, or a 200 whose document is the one already stored. */
    public void recordUnchanged(String etag) {
        update("UPDATE llm_catalog_state SET etag = ?, last_checked_at = now(), last_success_at = now(),"
                + " last_error = NULL WHERE id = 1", keptEtag(etag));
    }

    /** A fetch or a validation that failed; the stored document stays in use. */
    public void recordFailure(String reason) {
        update("UPDATE llm_catalog_state SET last_checked_at = now(), last_error = ? WHERE id = 1", reason);
    }

    private void update(String sql, String value) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update the LLM catalog state", e);
        }
    }

    private static void lockRow(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM llm_catalog_state WHERE id = 1 FOR UPDATE");
             ResultSet ignored = ps.executeQuery()) {
            // The row lock orders two instances storing at once; the later one wins whole.
        }
    }

    private static void notifyChanged(Connection c, String sha256) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, CHANNEL);
            ps.setString(2, sha256);
            ps.executeQuery().close();
        }
    }

    private static String keptEtag(String etag) {
        return etag != null && etag.length() <= MAX_ETAG ? etag : null;
    }

    private static LlmCatalogState toState(ResultSet rs) throws SQLException {
        return new LlmCatalogState(rs.getString("document"), rs.getString("sha256"), rs.getString("etag"),
                rs.getString("source"), rs.getString("url"), instant(rs.getTimestamp("loaded_at")),
                instant(rs.getTimestamp("last_checked_at")), instant(rs.getTimestamp("last_success_at")),
                rs.getString("last_error"), rs.getString("last_change"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
