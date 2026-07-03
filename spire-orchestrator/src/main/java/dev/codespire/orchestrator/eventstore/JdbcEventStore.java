package dev.codespire.orchestrator.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.EventStore;
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
 * <p>TODO(P1/SECURITY): payloads are stored as plaintext JSON bytes in this
 * Phase 0 skeleton (dev harness, synthetic data only). The Tink AES-GCM
 * envelope converter replaces this before any real code flows through —
 * the key_id column is already in place.
 */
@ApplicationScoped
public class JdbcEventStore implements EventStore {

    private static final String UNIQUE_VIOLATION = "23505";

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Override
    public List<EventEnvelope> load(String streamId) {
        String sql = """
                SELECT event_id, event_type, event_version, sequence, occurred_at,
                       correlation_id, causation_id, actor, payload
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
                VALUES (?, ?, ?, ?, ?, ?, 'none', ?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long sequence = expectedNextSequence;
            for (EventEnvelope e : events) {
                ps.setObject(1, e.eventId());
                ps.setString(2, streamId);
                ps.setLong(3, sequence++);
                ps.setString(4, e.eventType());
                ps.setInt(5, e.eventVersion());
                ps.setBytes(6, mapper.writeValueAsBytes(e.payload()));
                ps.setString(7, e.correlationId());
                ps.setObject(8, e.causationId());
                ps.setString(9, e.actor());
                ps.setTimestamp(10, Timestamp.from(e.occurredAt()));
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
        Object payload;
        try {
            payload = mapper.readValue(rs.getBytes("payload"), EventTypes.domainType(eventType));
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
