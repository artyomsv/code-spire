package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.HarnessSignInCommand;
import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing a harness in, from the operator's side (M3.5 part F).
 *
 * <p>A sign-in lives in its own table until the vendor has actually answered. It becomes a pool member
 * only on the way out — a half-filled {@code harness_credential} row would be visible to the rotation
 * query, and a member with no secret is one a run can select and then fail on.
 */
@ApplicationScoped
public class HarnessSignIns {

    private static final Logger LOG = Logger.getLogger(HarnessSignIns.class);

    /**
     * How long a unit may wait for a person.
     *
     * <p>The vendor states a 15-minute code. This is the deployment's own ceiling and is deliberately
     * a little shorter: a unit that outlives the code it is waiting on is a container held open for a
     * code that can no longer be typed.
     */
    static final Duration MAX_WAIT = Duration.ofMinutes(14);

    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject HarnessCredentialPool pool;

    /**
     * Which image drives which harness's sign-in.
     *
     * <p>The same map dispatch reads, so the CLI that holds the credential is the CLI that will use
     * it. A harness this deployment has no image for cannot be signed in, and says so before a
     * container starts rather than after one fails.
     */
    @Inject FactoryConfig config;

    @Inject @Channel("harness-sign-in-commands-out")
    Emitter<HarnessSignInCommand> commands;

    /** What a screen shows. Never a secret: the code authorises nothing without the account holder. */
    public record View(UUID id, String label, String harness, String state, String verificationUri,
                       String userCode, Instant expiresAt, String reason, UUID credentialId) {}

    /** A refusal a screen maps to a sentence, or the sign-in that started. */
    public record Started(View view, String refusal) {}

    /**
     * Starts one, unless this harness already has one in progress.
     *
     * <p>One at a time per harness, because two units racing for one seat is two codes on one screen
     * and a coin toss over which credential is stored.
     */
    public Started start(String label, String harness, String actor) {
        UUID id = UUID.randomUUID();
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                String image = config.agentImage().get(harness);
                if (image == null) return new Started(null, "harness_unconfigured");
                if (inProgress(c, harness).isPresent()) return new Started(null, "sign_in_already_running");
                if (pool.hasLabel(c, label)) return new Started(null, "harness_credential_label_taken");
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO harness_sign_in (id, label, harness, state, started_by)
                        VALUES (?, ?, ?, 'PENDING', ?)
                        """)) {
                    ps.setObject(1, id); ps.setString(2, label); ps.setString(3, harness); ps.setString(4, actor);
                    ps.executeUpdate();
                }
                // Sent inside the transaction, and the ack is awaited. If the broker never takes it
                // the row rolls back, so a screen never waits on a unit nobody was asked to start. The
                // opposite risk — a duplicate delivery — is the safe direction: the worker starts one
                // unit per command and the row it reports against is already there.
                KafkaSends.sendAndAwait(commands, id.toString(),
                        new HarnessSignInCommand.Start(id.toString(), harness, image, MAX_WAIT.toSeconds()),
                        "harness sign-in start for " + id);
                return new Started(read(c, id).orElseThrow(), null);
            } catch (SQLException failure) {
                throw new IllegalStateException("The sign-in could not be started", failure);
            }
        });
    }

    /** What the operator must do. Written straight through: the screen is polling for exactly this. */
    public void prompted(HarnessSignInResult.Prompted result) {
        update("""
                UPDATE harness_sign_in
                   SET state='PROMPTED', verification_uri=?, user_code=?, expires_at=?, updated_at=now()
                 WHERE id=? AND state='PENDING'
                """, ps -> {
            ps.setString(1, result.verificationUri());
            ps.setString(2, result.userCode());
            ps.setTimestamp(3, Timestamp.from(result.expiresAt()));
            ps.setObject(4, UUID.fromString(result.signInId()));
        });
    }

    /**
     * Turns a finished sign-in into a pool member.
     *
     * <p>The sealed bytes are opened here and nowhere else, and only to check the one field that has
     * been measured. A sign-in that came back as an API key is refused rather than stored under the
     * wrong kind: a subscription member that is really a key would be selected for subscription work
     * and then bill per token, which is the one thing this part exists to stop.
     */
    public void completed(HarnessSignInResult.Completed result) {
        UUID id = UUID.fromString(result.signInId());
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) {
                Optional<View> pending = read(c, id);
                if (pending.isEmpty() || !OPEN.contains(pending.get().state())) {
                    // Two cases, one rule: a sign-in that already became a member, and one the operator
                    // cancelled or that already failed. Only a sign-in still waiting may become a
                    // credential — otherwise a late delivery stores the very thing somebody declined,
                    // or a second member ends up holding the same seat, which the lease cannot
                    // reconcile and nothing afterwards can repair.
                    LOG.infof("ignoring a completion for sign-in %s, which is %s", id,
                            pending.map(View::state).orElse("gone"));
                    return;
                }
                if (isApiKeyMode(result.authMode())) {
                    fail(c, id, HarnessSignInResult.Failed.WRONG_MODE);
                    return;
                }
                String body = encryption.decryptString(result.sealedAuth(), HarnessSignInResult.sealedAad(result.signInId()));
                UUID credential = pool.addSubscription(c, pending.get().label(), pending.get().harness(), body);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE harness_sign_in SET state='COMPLETE', credential_id=?, updated_at=now() WHERE id=?
                        """)) {
                    ps.setObject(1, credential); ps.setObject(2, id); ps.executeUpdate();
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("The sign-in could not be stored", failure);
            }
        });
    }

    /** The states a sign-in may still be completed from. Anything else has already been decided. */
    private static final java.util.Set<String> OPEN = java.util.Set.of("PENDING", "PROMPTED");

    /** The vendor CLI's own word for an API-key sign-in. Anything else is a subscription. */
    private static boolean isApiKeyMode(String authMode) {
        return "apikey".equalsIgnoreCase(authMode);
    }

    public void failed(HarnessSignInResult.Failed result) {
        UUID id = UUID.fromString(result.signInId());
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) { fail(c, id, result.cause()); }
            catch (SQLException failure) { throw new IllegalStateException("The sign-in could not be recorded", failure); }
        });
    }

    private void fail(Connection c, UUID id, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE harness_sign_in SET state='FAILED', reason=?, updated_at=now()
                 WHERE id=? AND state IN ('PENDING','PROMPTED')
                """)) {
            ps.setString(1, reason); ps.setObject(2, id); ps.executeUpdate();
        }
    }

    /** The sign-in a screen is watching, if there is one. */
    public Optional<View> inProgress(String harness) {
        try (Connection c = dataSource.getConnection()) { return inProgress(c, harness); }
        catch (SQLException failure) { throw new IllegalStateException("The sign-in could not be read", failure); }
    }

    public Optional<View> get(UUID id) {
        try (Connection c = dataSource.getConnection()) { return read(c, id); }
        catch (SQLException failure) { throw new IllegalStateException("The sign-in could not be read", failure); }
    }

    /** Stops one the operator gave up on, and tells the worker so the container does not sit waiting. */
    public boolean cancel(UUID id, String reason) {
        KafkaSends.sendAndAwait(commands, id.toString(), new HarnessSignInCommand.Cancel(id.toString(), reason),
                "harness sign-in cancel for " + id);
        return update("""
                UPDATE harness_sign_in SET state='FAILED', reason=?, updated_at=now()
                 WHERE id=? AND state IN ('PENDING','PROMPTED')
                """, ps -> { ps.setString(1, HarnessSignInResult.Failed.CANCELLED); ps.setObject(2, id); }) == 1;
    }

    private Optional<View> inProgress(Connection c, String harness) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, label, harness, state, verification_uri, user_code, expires_at, reason, credential_id
                  FROM harness_sign_in
                 WHERE harness=? AND state IN ('PENDING','PROMPTED')
                 ORDER BY created_at DESC LIMIT 1
                """)) {
            ps.setString(1, harness);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(view(rs)) : Optional.empty(); }
        }
    }

    private Optional<View> read(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, label, harness, state, verification_uri, user_code, expires_at, reason, credential_id
                  FROM harness_sign_in WHERE id=?
                """)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(view(rs)) : Optional.empty(); }
        }
    }

    private static View view(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new View(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("harness"),
                rs.getString("state"), rs.getString("verification_uri"), rs.getString("user_code"),
                expires == null ? null : expires.toInstant(), rs.getString("reason"),
                rs.getObject("credential_id", UUID.class));
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private int update(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("The sign-in could not be updated", failure);
        }
    }
}
