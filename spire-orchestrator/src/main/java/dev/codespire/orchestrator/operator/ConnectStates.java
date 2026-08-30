package dev.codespire.orchestrator.operator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The one-attempt binding between a browser that started a connect and the callback that returns.
 *
 * <p>Without it a third party could hand an operator a crafted callback URL and link <em>their</em>
 * SCM account to the operator's dashboard identity — the operator would then be measured as
 * somebody else, and the screen would look entirely normal.
 *
 * <p>A state is <b>consumed</b>, not merely checked: the delete and the read happen in one statement,
 * so a replayed callback finds nothing. Reusing one would let a single intercepted URL be redeemed
 * repeatedly.
 */
@ApplicationScoped
public class ConnectStates {

    /**
     * How long a started connect stays redeemable.
     *
     * <p>Long enough for a real sign-in — a password, a second factor, and a consent screen — and
     * short enough that a URL captured from a browser history is worthless by the time it is read.
     */
    static final Duration LIFETIME = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Pending(String oidcSubject, String providerType, String redirectUri) {
    }

    @Inject
    DataSource dataSource;

    /** Starts an attempt and returns the state to send to the SCM. */
    @Transactional
    public String start(String oidcSubject, String providerType, String redirectUri) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String sql = """
                INSERT INTO oauth_connect_state (state, oidc_subject, provider_type, redirect_uri)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setString(2, oidcSubject);
            ps.setString(3, providerType);
            ps.setString(4, redirectUri);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to start an SCM connect", e);
        }
        sweepExpired();
        return state;
    }

    /**
     * Redeems a state exactly once.
     *
     * <p>Expiry is enforced in the same statement as the delete, so a state that is too old is
     * consumed and refused rather than left behind for a later attempt.
     */
    @Transactional
    public Optional<Pending> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                DELETE FROM oauth_connect_state
                 WHERE state = ?
             RETURNING oidc_subject, provider_type, redirect_uri, created_at
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                if (rs.getTimestamp("created_at").toInstant().plus(LIFETIME).isBefore(Instant.now())) {
                    return Optional.empty();
                }
                return Optional.of(new Pending(rs.getString("oidc_subject"),
                        rs.getString("provider_type"), rs.getString("redirect_uri")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to redeem an SCM connect", e);
        }
    }

    /**
     * Drops states nobody came back for.
     *
     * <p>Run on each start rather than on a timer: an abandoned attempt is only ever created by a
     * start, so that is exactly when the table can have grown, and a sweep there needs no scheduler
     * to be correct on a deployment that is used once a week.
     */
    private void sweepExpired() {
        // Bound from LIFETIME rather than written as a SQL interval: two spellings of one deadline
        // drift, and the half that drifts here is the half nothing would notice.
        String sql = "DELETE FROM oauth_connect_state WHERE created_at < ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(LIFETIME)));
            ps.executeUpdate();
        } catch (SQLException e) {
            // A full table is a tidiness problem, not a correctness one -- `consume` checks the age
            // itself. Failing the operator's sign-in over it would trade a real feature for a chore.
            org.jboss.logging.Logger.getLogger(ConnectStates.class)
                    .warn("could not sweep expired SCM connect states", e);
        }
    }
}
