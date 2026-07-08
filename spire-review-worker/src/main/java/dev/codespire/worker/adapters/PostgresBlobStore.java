package dev.codespire.worker.adapters;

import dev.codespire.contract.port.BlobStore;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Postgres-backed {@link BlobStore} for the assembled review context (DATA-MODEL §4).
 * Payloads are Tink-encrypted here, client-side, before the write — the DB only
 * ever sees ciphertext, bound by AAD to its {@code reviewId} so a row cannot be
 * swapped between reviews. Worker-owned (schema-per-service), same as
 * {@link CommentIdempotencyStore}.
 *
 * <p>{@code review_id} is stored alongside the key so a review's blobs are always
 * reachable for deletion by {@link #deleteByReview}: content and reference are the
 * same row, so there is nothing to orphan.
 */
@ApplicationScoped
public class PostgresBlobStore implements BlobStore {

    /** The assembled context is serialized JSON before encryption. */
    private static final String MEDIA_TYPE = "application/json";

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    @Override
    public BlobRef put(Kind kind, String reviewId, byte[] plaintext) {
        String contextId = UUID.randomUUID().toString();
        String aad = reviewId;
        byte[] ciphertext = encryption.encrypt(plaintext, aad);
        String sql = """
                INSERT INTO context_blob
                    (context_id, review_id, kind, ciphertext, aad, size_bytes, media_type, filename)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, contextId);
            ps.setString(2, reviewId);
            ps.setString(3, kind.name());
            ps.setBytes(4, ciphertext);
            ps.setString(5, aad);
            ps.setInt(6, plaintext.length);
            ps.setString(7, MEDIA_TYPE);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("context_blob insert failed", e);
        }
        return new BlobRef(contextId);
    }

    @Override
    public byte[] get(BlobRef ref) {
        String sql = "SELECT ciphertext, aad FROM context_blob WHERE context_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ref.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return encryption.decrypt(rs.getBytes("ciphertext"), rs.getString("aad"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("context_blob read failed", e);
        }
    }

    @Override
    public void delete(BlobRef ref) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM context_blob WHERE context_id = ?")) {
            ps.setString(1, ref.key());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("context_blob delete failed", e);
        }
    }

    /**
     * Clears every blob a review owns — the re-assembly guard (before a fresh
     * write, so a redelivered GatherContext never accumulates) and the re-run
     * cleanup both call this. Deletion on review delete happens in the
     * orchestrator's transaction against {@code worker.context_blob} directly.
     *
     * @return the number of blobs removed
     */
    public int deleteByReview(String reviewId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM context_blob WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("context_blob delete-by-review failed", e);
        }
    }
}
