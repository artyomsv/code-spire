package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The specification and plan this deployment composed for an item, stored as the bytes an approval
 * binds (M3.5 part C, decision 1B).
 *
 * <p>Insert only. A later preparation writes new rows rather than replacing these, because a gate that
 * is already open and a build that is already held both point at the exact bytes they were given —
 * re-preparing must not rewrite the text somebody approved.
 *
 * <p>Every read is scoped to the owning item. An artifact id is carried in a preparation, which is
 * carried in an item's history; asking for one by id alone would let any admin screen read any item's
 * stored text by guessing a UUID.
 */
@ApplicationScoped
public class WorkItemArtifacts {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    /** Which of the two texts a row holds. */
    public enum Kind { SPEC, PLAN }

    /** The AAD: the row's own id, so ciphertext moved to another row cannot be decrypted. */
    private static String aad(UUID id) { return "work-item-artifact:" + id; }

    /**
     * Stores one text and answers its identity and digest. The caller writes both artifacts before it
     * attempts the preparation, so a refused preparation leaves rows nothing references; they are inert
     * and immutable, and the alternative — writing them inside the item's transaction — would hold that
     * transaction open across an encryption call for no gain.
     */
    public UUID store(Connection c, String workItemId, Kind kind, String body) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO work_item_artifact(id,work_item_id,kind,body,sha256) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, workItemId);
            ps.setString(3, kind.name());
            ps.setBytes(4, encryption.encrypt(body.getBytes(StandardCharsets.UTF_8), aad(id)));
            ps.setString(5, WorkPreparation.digest(body));
            ps.executeUpdate();
        }
        return id;
    }

    /** The stored text of one artifact of one item, or empty when this item has no such row. */
    public Optional<String> read(Connection c, String workItemId, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT body FROM work_item_artifact WHERE id=? AND work_item_id=?")) {
            ps.setObject(1, id);
            ps.setString(2, workItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new String(encryption.decrypt(rs.getBytes(1), aad(id)), StandardCharsets.UTF_8));
            }
        }
    }

    /** As {@link #read(Connection, String, UUID)}, on its own connection. */
    public Optional<String> read(String workItemId, UUID id) {
        try (Connection c = dataSource.getConnection()) { return read(c, workItemId, id); }
        catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
}
