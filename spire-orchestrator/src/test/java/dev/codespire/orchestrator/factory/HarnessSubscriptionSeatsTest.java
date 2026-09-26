package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signed-in seats are shared by builds, one seat per account (M3.5 part F; operator decision 2026-09-26).
 *
 * <p>Uses its own harness name so no other suite's seats are in range, and switches every seat it made
 * off afterwards: a seat a finished run points at cannot be deleted, by design.
 */
@QuarkusTest
class HarnessSubscriptionSeatsTest {

    @Inject HarnessCredentialPool pool;
    @Inject DataSource dataSource;

    private static final String HARNESS = "TEST-seat-harness";
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

    /** Shared like a key: an agent's copy has no refresh token, so two agents cannot log each other out. */
    @Test
    void aSeatServesSeveralBuildsAtOnce() throws SQLException {
        UUID seat = seat("TEST-seat-shared");

        assertEquals(seat, pool.selectSubscription(HARNESS).orElseThrow().id());
        assertEquals(seat, pool.selectSubscription(HARNESS).orElseThrow().id());
    }

    @Test
    void theSelectedSeatCarriesItsWholeStoredFile() throws SQLException {
        seat("TEST-seat-file");

        assertTrue(pool.selectSubscription(HARNESS).orElseThrow().apiKey().contains("TEST-refresh"),
                "the whole stored file; the dispatch empties its refresh token");
    }

    /**
     * A dispatch picking the only seat at the same moment as another waits for it rather than being
     * refused: the seat is shared (review of PR #178).
     */
    @Test
    void aSeatBeingPickedByAnotherDispatchIsWaitedForNotSkipped() throws Exception {
        UUID seat = seat("TEST-seat-contended");
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM harness_credential WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, seat);
                lock.executeQuery().close();
            }
            CompletableFuture<java.util.Optional<HarnessCredentialPool.PoolMember>> picking =
                    CompletableFuture.supplyAsync(() -> pool.selectSubscription(HARNESS));

            assertThrows(TimeoutException.class, () -> picking.get(1, TimeUnit.SECONDS), "it waits, it does not skip");
            other.commit();
            assertEquals(seat, picking.get(30, TimeUnit.SECONDS).orElseThrow().id());
        }
    }

    /** A seat switched off while a pick waits for it is not handed out when the wait ends. */
    @Test
    void aSeatSwitchedOffDuringAWaitIsNotHandedOut() throws Exception {
        UUID seat = seat("TEST-seat-switched-off");
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement off = other.prepareStatement("UPDATE harness_credential SET enabled = FALSE WHERE id = ?")) {
                off.setObject(1, seat);
                off.executeUpdate();
            }
            CompletableFuture<java.util.Optional<HarnessCredentialPool.PoolMember>> picking =
                    CompletableFuture.supplyAsync(() -> pool.selectSubscription(HARNESS));

            assertThrows(TimeoutException.class, () -> picking.get(1, TimeUnit.SECONDS));
            other.commit();
            assertTrue(picking.get(30, TimeUnit.SECONDS).isEmpty(), "a switched-off seat pays for nothing");
        }
    }

    /** Two seats take turns, least recently used first, like the key pool. */
    @Test
    void seatsTakeTurns() throws SQLException {
        UUID first = seat("TEST-seat-first");
        UUID second = seat("TEST-seat-second");

        UUID a = pool.selectSubscription(HARNESS).orElseThrow().id();
        UUID b = pool.selectSubscription(HARNESS).orElseThrow().id();

        assertNotEquals(a, b);
        assertEquals(Set.of(first, second), Set.of(a, b));
    }

    /** A seat whose account is unknown could be a second seat on one account, so it is never used. */
    @Test
    void aSeatWithNoKnownAccountIsNeverUsed() throws SQLException {
        UUID seat = seat("TEST-seat-unidentified");
        sql("UPDATE harness_credential SET account_ref = NULL WHERE id = ?", seat);

        assertTrue(pool.selectSubscription(HARNESS).isEmpty());
        assertFalse(pool.hasSubscription(HARNESS), "a setup cannot be saved on a seat no build may use");
        assertFalse(view(seat).identified());
    }

    /** A sign-in that names no account is not stored as a seat at all. */
    @Test
    void aSignInWithNoAccountIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> {
            try (Connection c = dataSource.getConnection()) {
                pool.addSubscription(c, "TEST-seat-no-account-" + UUID.randomUUID(), HARNESS, "{\"auth_mode\":\"TEST\"}");
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
        UUID older = seatOn("TEST-seat-older", account);
        sql("UPDATE harness_credential SET account_ref = NULL, updated_at = now() - interval '2 days' WHERE id = ?", older);
        UUID newer = seatOn("TEST-seat-newer", account);
        sql("UPDATE harness_credential SET account_ref = NULL, updated_at = now() - interval '1 day' WHERE id = ?", newer);

        assertTrue(pool.identifySeats() >= 1);

        assertTrue(view(newer).enabled() && view(newer).identified());
        assertFalse(view(older).enabled(), "one account has one seat");
        assertThrows(HarnessCredentialPool.SeatTakenException.class, () -> pool.enable(older));
    }

    /**
     * Two orchestrators starting together identify one after the other, never side by side: side by side,
     * the second would find the first's work and switch the surviving seat off (review of PR #178).
     */
    @Test
    void identificationWaitsForAnotherOrchestratorDoingTheSame() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, HarnessCredentialPool.IDENTIFY_LOCK);
                lock.execute();
            }
            CompletableFuture<Integer> identifying = CompletableFuture.supplyAsync(pool::identifySeats);

            assertThrows(TimeoutException.class, () -> identifying.get(1, TimeUnit.SECONDS),
                    "it must wait while another orchestrator holds the lock");
            other.commit();
            assertNotNull(identifying.get(30, TimeUnit.SECONDS));
        }
    }

    /** API-key selection never reaches a seat: that path hands its credential out whole, to anyone. */
    @Test
    void theKeySelectorNeverHandsOutASeat() throws SQLException {
        UUID seat = seat("TEST-seat-containment");

        HarnessCredentialPool.Selection selection = pool.select();

        assertFalse(selection instanceof HarnessCredentialPool.Selection.Chosen chosen && chosen.member().id().equals(seat));
    }

    @Test
    void aSeatOfAnotherHarnessIsNotOffered() throws SQLException {
        seat("TEST-seat-other");

        assertTrue(pool.selectSubscription("TEST-no-such-harness").isEmpty());
        assertTrue(pool.hasSubscription(HARNESS));
        assertFalse(pool.hasSubscription("TEST-no-such-harness"));
    }
}
