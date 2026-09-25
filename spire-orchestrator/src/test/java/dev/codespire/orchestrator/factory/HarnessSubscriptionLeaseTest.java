package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A signed-in seat serves one build at a time (M3.5 part F, design §5.5).
 *
 * <p>Uses its own harness name so no other suite's seats are in range, and switches every seat it made
 * off afterwards: a seat a finished run points at cannot be deleted, by design.
 */
@QuarkusTest
class HarnessSubscriptionLeaseTest {

    @Inject HarnessCredentialPool pool;
    @Inject DataSource dataSource;

    private static final String HARNESS = "TEST-lease-harness";
    private static final String FILE = "{\"auth_mode\":\"TEST\",\"tokens\":{\"refresh_token\":\"TEST-refresh\"}}";
    private final List<UUID> seats = new ArrayList<>();

    @AfterEach
    void switchSeatsOff() {
        seats.forEach(pool::remove);
    }

    private UUID seat(String label) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID id = pool.addSubscription(c, label + "-" + UUID.randomUUID(), HARNESS, FILE);
            seats.add(id);
            return id;
        }
    }

    private static Instant inAnHour() {
        return Instant.now().plusSeconds(3600);
    }

    @Test
    void aLeasedSeatIsNotHandedToASecondRun() throws SQLException {
        UUID seat = seat("TEST-lease-one");

        Optional<HarnessCredentialPool.PoolMember> first = pool.selectSubscription(HARNESS, "TEST-run-1", inAnHour());
        Optional<HarnessCredentialPool.PoolMember> second = pool.selectSubscription(HARNESS, "TEST-run-2", inAnHour());

        assertEquals(seat, first.orElseThrow().id());
        assertEquals(FILE, first.orElseThrow().apiKey(), "the whole stored file; the dispatch empties its refresh token");
        assertTrue(second.isEmpty(), "one sign-in, one agent");
    }

    /**
     * A dispatch the broker definitely missed is assembled again under the same run id; the seat it
     * leased and never used is still its own (review of PR #178).
     */
    @Test
    void theSameRunGetsItsOwnSeatBackAndNoOtherRunDoes() throws SQLException {
        UUID seat = seat("TEST-lease-reentrant");
        pool.selectSubscription(HARNESS, "TEST-run-missed", inAnHour()).orElseThrow();

        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-other", inAnHour()).isEmpty());
        assertEquals(seat, pool.selectSubscription(HARNESS, "TEST-run-missed", inAnHour()).orElseThrow().id());
    }

    /** Fenced by run id: a late release from an earlier run cannot free a seat another run is using. */
    @Test
    void onlyTheRunHoldingTheLeaseCanRelease() throws SQLException {
        seat("TEST-lease-fence");
        pool.selectSubscription(HARNESS, "TEST-run-holder", inAnHour()).orElseThrow();

        pool.releaseLease("TEST-run-someone-else");
        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-next", inAnHour()).isEmpty());

        pool.releaseLease("TEST-run-holder");
        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-next", inAnHour()).isPresent());
    }

    /** A lease whose release never arrives ends by itself, so a seat is never held for ever. */
    @Test
    void aLeaseThatRanOutFreesTheSeat() throws SQLException {
        UUID seat = seat("TEST-lease-expired");
        pool.selectSubscription(HARNESS, "TEST-run-lost", inAnHour()).orElseThrow();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE harness_credential SET leased_until=now()-interval '1 minute' WHERE id=?")) {
            ps.setObject(1, seat);
            ps.executeUpdate();
        }

        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-after", inAnHour()).isPresent());
    }

    /** The screen says "in use" only while a lease is live. */
    @Test
    void theListSaysWhenASeatIsInUse() throws SQLException {
        UUID seat = seat("TEST-lease-view");
        pool.selectSubscription(HARNESS, "TEST-run-view", inAnHour()).orElseThrow();

        var view = pool.list().stream().filter(member -> member.id().equals(seat)).findFirst().orElseThrow();
        assertNotNull(view.leasedUntil());

        pool.releaseLease("TEST-run-view");
        assertNull(pool.list().stream().filter(member -> member.id().equals(seat)).findFirst().orElseThrow().leasedUntil());
    }

    /** API-key selection never reaches a seat: that path hands its credential out whole, to anyone. */
    @Test
    void theKeySelectorNeverHandsOutASeat() throws SQLException {
        UUID seat = seat("TEST-lease-containment");

        HarnessCredentialPool.Selection selection = pool.select();

        assertFalse(selection instanceof HarnessCredentialPool.Selection.Chosen chosen && chosen.member().id().equals(seat));
    }

    @Test
    void aSeatOfAnotherHarnessIsNotOffered() throws SQLException {
        seat("TEST-lease-other");

        assertTrue(pool.selectSubscription("TEST-no-such-harness", "TEST-run-x", inAnHour()).isEmpty());
        assertTrue(pool.hasSubscription(HARNESS));
        assertFalse(pool.hasSubscription("TEST-no-such-harness"));
    }
}
