package dev.codespire.orchestrator.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.EventStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres event store against a real database (DevServices): append/load
 * round trip with Tink encryption at rest, the optimistic-concurrency conflict
 * on a stale expectedNextSequence (UNIQUE(stream_id, sequence) -> 23505 ->
 * ConcurrencyException), and the legacy key_id='none' plaintext read branch.
 */
@QuarkusTest
class JdbcEventStoreTest {

    @Inject
    JdbcEventStore store;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Test
    void appendAndLoadRoundTrip() {
        String stream = "review::es-qa/round-trip#1";
        EventEnvelope first = EventEnvelope.domain(stream, -1, stream, null,
                new DomainEvent.ReviewRequested("cafe111", "OPENED"));
        EventEnvelope second = EventEnvelope.domain(stream, -1, stream, null,
                new DomainEvent.ReviewCompleted("cafe111", "comment-9"));

        store.append(stream, 0, List.of(first, second));

        List<EventEnvelope> loaded = store.load(stream);
        assertEquals(2, loaded.size());
        assertEquals(0, loaded.get(0).sequence(), "sequences assigned from expectedNextSequence");
        assertEquals(1, loaded.get(1).sequence());
        assertEquals(first.eventId(), loaded.get(0).eventId());
        assertEquals("ReviewRequested", loaded.get(0).eventType());
        DomainEvent.ReviewRequested requested =
                assertInstanceOf(DomainEvent.ReviewRequested.class, loaded.get(0).payload());
        assertEquals("cafe111", requested.commit());
        assertEquals("OPENED", requested.trigger());
        DomainEvent.ReviewCompleted completed =
                assertInstanceOf(DomainEvent.ReviewCompleted.class, loaded.get(1).payload());
        assertEquals("comment-9", completed.summaryCommentId());
    }

    @Test
    void payloadsAreTinkEncryptedAtRest() throws Exception {
        String stream = "review::es-qa/at-rest#2";
        store.append(stream, 0, List.of(EventEnvelope.domain(stream, -1, stream, null,
                new DomainEvent.ReviewRequested("SECRET-COMMIT-MARKER", "OPENED"))));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload, key_id FROM event_log WHERE stream_id = ?")) {
            ps.setString(1, stream);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("tink", rs.getString("key_id"));
                String raw = new String(rs.getBytes("payload"), StandardCharsets.UTF_8);
                assertFalse(raw.contains("SECRET-COMMIT-MARKER"), "payload must be ciphertext at rest");
            }
        }
    }

    @Test
    void staleExpectedSequenceRaisesConcurrencyConflict() {
        String stream = "review::es-qa/conflict#3";
        store.append(stream, 0, List.of(EventEnvelope.domain(stream, -1, stream, null,
                new DomainEvent.ReviewRequested("beef222", "OPENED"))));

        // A second writer folded the same (now stale) history: expectedNextSequence 0
        // again -> UNIQUE(stream_id, sequence) 23505 -> ConcurrencyException.
        assertThrows(EventStore.ConcurrencyException.class, () ->
                store.append(stream, 0, List.of(EventEnvelope.domain(stream, -1, stream, null,
                        new DomainEvent.ReviewSuperseded("beef333")))));

        assertEquals(1, store.load(stream).size(), "the losing append must not partially persist");
    }

    @Test
    void legacyPlaintextRowsStillRead() throws Exception {
        String stream = "review::es-qa/legacy#4";
        UUID eventId = UUID.randomUUID();
        // A pre-Tink row: key_id='none', payload stored as plain JSON bytes.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO event_log (event_id, stream_id, sequence, event_type, event_version,
                                            payload, key_id, actor, occurred_at)
                     VALUES (?, ?, 0, 'ReviewRequested', 1, ?, 'none', 'system', ?)
                     """)) {
            ps.setObject(1, eventId);
            ps.setString(2, stream);
            ps.setBytes(3, mapper.writeValueAsBytes(new DomainEvent.ReviewRequested("dead444", "OPENED")));
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }

        List<EventEnvelope> loaded = store.load(stream);
        assertEquals(1, loaded.size());
        assertEquals(eventId, loaded.get(0).eventId());
        DomainEvent.ReviewRequested payload =
                assertInstanceOf(DomainEvent.ReviewRequested.class, loaded.get(0).payload());
        assertEquals("dead444", payload.commit(), "key_id='none' rows are read as plaintext");
    }
}
