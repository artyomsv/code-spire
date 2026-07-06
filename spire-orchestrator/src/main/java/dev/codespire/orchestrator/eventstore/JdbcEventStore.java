package dev.codespire.orchestrator.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.EventStore;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Postgres append-only event store (DATA-MODEL §3). UNIQUE(stream_id, sequence)
 * enforces single-writer optimistic concurrency.
 *
 * <p>Payloads are encrypted at rest with Tink AES-GCM ({@link EncryptionService},
 * ADR-009); {@code key_id='tink'} marks encrypted rows, {@code 'none'} legacy
 * plaintext. The stream id is the associated data, binding each ciphertext to
 * its stream.
 */
@ApplicationScoped
public class JdbcEventStore implements EventStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String KEY_ID = "tink"; // marks a Tink-encrypted payload ('none' = legacy plaintext)

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    EncryptionService encryption;

    @Override
    public List<EventEnvelope> load(String streamId) {
        String sql = """
                SELECT event_id, event_type, event_version, sequence, occurred_at,
                       correlation_id, causation_id, actor, payload, key_id
                FROM event_log WHERE stream_id = ? ORDER BY sequence
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, streamId);
            List<EventEnvelope> events = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(map(streamId, rs));
                }
            }
            return events;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load stream " + streamId, e);
        }
    }

    @Override
    public void append(String streamId, long expectedNextSequence, List<EventEnvelope> events) {
        String sql = """
                INSERT INTO event_log (event_id, stream_id, sequence, event_type, event_version,
                                       payload, key_id, correlation_id, causation_id, actor, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long sequence = expectedNextSequence;
            for (EventEnvelope e : events) {
                ps.setObject(1, e.eventId());
                ps.setString(2, streamId);
                ps.setLong(3, sequence++);
                ps.setString(4, e.eventType());
                ps.setInt(5, e.eventVersion());
                // Encrypt at rest; the stream id as AAD binds ciphertext to its stream.
                ps.setBytes(6, encryption.encrypt(mapper.writeValueAsBytes(e.payload()), streamId));
                ps.setString(7, KEY_ID);
                ps.setString(8, e.correlationId());
                ps.setObject(9, e.causationId());
                ps.setString(10, e.actor());
                ps.setTimestamp(11, Timestamp.from(e.occurredAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())
                    || (e.getNextException() != null && UNIQUE_VIOLATION.equals(e.getNextException().getSQLState()))) {
                throw new ConcurrencyException(streamId, expectedNextSequence);
            }
            throw new IllegalStateException("Failed to append to stream " + streamId, e);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private EventEnvelope map(String streamId, ResultSet rs) throws SQLException {
        String eventType = rs.getString("event_type");
        byte[] stored = rs.getBytes("payload");
        // Decrypt Tink-encrypted payloads; 'none' rows are legacy plaintext.
        byte[] plaintext = KEY_ID.equals(rs.getString("key_id")) ? encryption.decrypt(stored, streamId) : stored;
        Object payload;
        try {
            payload = mapper.readValue(plaintext, EventTypes.domainType(eventType));
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt payload for " + eventType + " in " + streamId, e);
        }
        return new EventEnvelope(
                rs.getObject("event_id", UUID.class),
                eventType,
                rs.getInt("event_version"),
                streamId,
                rs.getLong("sequence"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"),
                rs.getObject("causation_id", UUID.class),
                rs.getString("actor"),
                payload);
    }
}
