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
    private final List<UUID> seats = new ArrayList<>();

    /** A sign-in file of its own account: one account has one enabled seat. */
    private static String file(String account) {
        return "{\"auth_mode\":\"TEST\",\"tokens\":{\"refresh_token\":\"TEST-refresh\",\"account_id\":\"" + account + "\"}}";
    }

    private static String anAccount() {
        return "TEST-account-" + UUID.randomUUID();
    }

    @AfterEach
    void switchSeatsOff() {
        seats.forEach(pool::remove);
    }

    private UUID seat(String label) throws SQLException {
        return seatOn(label, anAccount());
    }

    private UUID seatOn(String label, String account) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID id = pool.addSubscription(c, label + "-" + UUID.randomUUID(), HARNESS, file(account));
            seats.add(id);
            return id;
        }
    }

    private void sql(String statement, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private HarnessCredentialPool.MemberView view(UUID seat) {
        return pool.list().stream().filter(member -> member.id().equals(seat)).findFirst().orElseThrow();
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
        assertTrue(first.orElseThrow().apiKey().contains("TEST-refresh"), "the whole stored file; the dispatch empties its refresh token");
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
        assertTrue(view(seat).inUse());

        pool.holdWhileRunning("TEST-run-view");
        assertTrue(view(seat).inUse(), "a running agent's seat is in use");

        pool.releaseLease("TEST-run-view");
        assertFalse(view(seat).inUse());
    }

    /**
     * Once the agent starts, no clock frees its seat: only the worker's word that the agent stopped does
     * (review of PR #178 — a failed stop can leave an agent running long past its wall clock).
     */
    @Test
    void aRunningAgentsSeatIsNeverFreedByTheClock() throws SQLException {
        UUID seat = seat("TEST-lease-running");
        pool.selectSubscription(HARNESS, "TEST-run-running", Instant.now().minusSeconds(60)).orElseThrow();
        pool.holdWhileRunning("TEST-run-running");

        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-other", inAnHour()).isEmpty());

        pool.releaseLease("TEST-run-running");
        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-other", inAnHour()).isPresent());
    }

    /** A worker that died for good never reports; an operator frees the seat. */
    @Test
    void anOperatorCanFreeASeatWhoseWorkerIsGone() throws SQLException {
        UUID seat = seat("TEST-lease-freed");
        assertFalse(pool.freeSeat(seat), "a seat nobody holds has nothing to free");
        pool.selectSubscription(HARNESS, "TEST-run-lost-worker", inAnHour()).orElseThrow();
        pool.holdWhileRunning("TEST-run-lost-worker");

        assertTrue(pool.freeSeat(seat));

        assertFalse(view(seat).inUse());
        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-after-freeing", inAnHour()).isPresent());
    }

    /** A seat whose account is unknown could be a second seat on one account, so it is never leased. */
    @Test
    void aSeatWithNoKnownAccountIsNeverLeased() throws SQLException {
        UUID seat = seat("TEST-lease-unidentified");
        sql("UPDATE harness_credential SET account_ref = NULL WHERE id = ?", seat);

        assertTrue(pool.selectSubscription(HARNESS, "TEST-run-unidentified", inAnHour()).isEmpty());
        assertFalse(view(seat).identified());
    }

    /** A sign-in that names no account is not stored as a seat at all. */
    @Test
    void aSignInWithNoAccountIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> {
            try (Connection c = dataSource.getConnection()) {
                pool.addSubscription(c, "TEST-lease-no-account-" + UUID.randomUUID(), HARNESS, "{\"auth_mode\":\"TEST\"}");
            }
        });
        assertEquals("subscription_unidentified", refused.getMessage());
    }

    /**
     * Seats stored before accounts were recorded are identified from their own files. The newest seat of
     * an account keeps it; an older one is switched off, and cannot be switched back on beside it.
     */
    @Test
    void identifyingOldSeatsKeepsTheNewestSeatOfAnAccount() throws SQLException {
        String account = anAccount();
        UUID older = seatOn("TEST-lease-older", account);
        sql("UPDATE harness_credential SET account_ref = NULL, updated_at = now() - interval '2 days' WHERE id = ?", older);
        UUID newer = seatOn("TEST-lease-newer", account);
        sql("UPDATE harness_credential SET account_ref = NULL, updated_at = now() - interval '1 day' WHERE id = ?", newer);

        assertTrue(pool.identifySeats() >= 1);

        assertTrue(view(newer).enabled() && view(newer).identified());
        assertFalse(view(older).enabled(), "a second seat on one account is a second lease on one sign-in");
        assertThrows(HarnessCredentialPool.SeatTakenException.class, () -> pool.enable(older));
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
