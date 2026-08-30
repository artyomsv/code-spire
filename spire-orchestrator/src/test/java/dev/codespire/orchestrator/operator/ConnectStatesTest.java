package dev.codespire.orchestrator.operator;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binding that stops one operator being handed a link to somebody else's SCM account.
 *
 * <p>Without it a crafted callback URL would link the sender's account to whoever clicked it: the
 * recipient would then be measured as that person, and every screen would look normal. The whole
 * value of proving an identity by sign-in rests on these four tests.
 */
@QuarkusTest
class ConnectStatesTest {

    private static final String ALICE = "TEST-SUBJECT-ALICE";

    @Inject
    ConnectStates states;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM oauth_connect_state WHERE oidc_subject LIKE 'TEST-SUBJECT-%'");
    }

    @Test
    void redeemsAStateItIssued() {
        String state = states.start(ALICE, "github", "https://spire.example.invalid/cb");

        ConnectStates.Pending pending = states.consume(state).orElseThrow();

        assertEquals(ALICE, pending.oidcSubject());
        assertEquals("github", pending.providerType());
        assertEquals("https://spire.example.invalid/cb", pending.redirectUri());
    }

    /**
     * Consumed, not merely checked. A state that survived redemption would let one intercepted
     * callback URL be replayed for as long as it lived.
     */
    @Test
    void redeemsAStateOnlyOnce() {
        String state = states.start(ALICE, "github", "https://spire.example.invalid/cb");

        assertTrue(states.consume(state).isPresent());
        assertEquals(Optional.empty(), states.consume(state));
    }

    @Test
    void refusesAStateItNeverIssued() {
        assertEquals(Optional.empty(), states.consume("TEST-STATE-NOBODY-ISSUED"));
        // A missing parameter is the shape a hand-written callback URL arrives in.
        assertEquals(Optional.empty(), states.consume(null));
        assertEquals(Optional.empty(), states.consume(""));
    }

    /**
     * An expired state is refused AND consumed. Leaving it behind would let an attempt that timed
     * out be redeemed later — which is the case the expiry exists to close.
     */
    @Test
    void refusesAStateThatSatTooLong() {
        String state = states.start(ALICE, "github", "https://spire.example.invalid/cb");
        age(state, ConnectStates.LIFETIME.plusMinutes(1));

        assertEquals(Optional.empty(), states.consume(state));
        assertEquals(Optional.empty(), states.consume(state), "an expired state is not left behind");
    }

    /** Unguessable, and never the same twice — a predictable state is no binding at all. */
    @Test
    void issuesAnUnguessableStateEachTime() {
        String first = states.start(ALICE, "github", "https://spire.example.invalid/cb");
        String second = states.start(ALICE, "github", "https://spire.example.invalid/cb");

        assertNotEquals(first, second);
        assertTrue(first.length() >= 32, "a short state is brute-forceable within its lifetime");
    }

    private void age(String state, java.time.Duration by) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE oauth_connect_state SET created_at = ? WHERE state = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(by)));
            ps.setString(2, state);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not age a connect state", e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
