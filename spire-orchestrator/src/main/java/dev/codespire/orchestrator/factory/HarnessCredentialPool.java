package dev.codespire.orchestrator.factory;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
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
 * The keys a factory run may call the model with, and the order they are handed out in (FR-F12).
 *
 * <p><b>Separate from the reviewer's key, not shared carefully.</b> A run's credential goes to an
 * agent running an untrusted model on an untrusted work item at full shell access, which can read
 * its own environment. The reviewer's key never leaves this process. Taking the deployment's default
 * LLM provider for a run — which is what happened before this class — meant one exfiltration
 * disabled reviews and runs together, and a spend spike from a leaked key looked exactly like
 * legitimate factory use in the ledger. {@code scm_provider.role} settled the same argument for the
 * push identity; this is the model side of it.
 *
 * <p><b>A pool, because the factory's failure mode is exhaustion.</b> A reviewer makes one call and
 * reports a failure to a person. An agent works for an hour and can exhaust a key's quota mid-run,
 * and the runs behind it must not simply queue up behind a dead credential.
 *
 * <p><b>Two exhaustion states, because they need opposite treatment.</b> A rate limit is a promise —
 * capacity returns at a stated time and the pool heals itself. A rejection is an answer — the key is
 * wrong, revoked, or out of credit, and retrying it spends one request per run to learn nothing. A
 * single "unavailable until" column forces one of those to be wrong, and the direction that reads a
 * rejection as a pause is how a pool stops rotating while still looking healthy.
 *
 * <p><b>Neither state has an automatic producer today, and that is the honest statement of what this
 * class currently is.</b> See {@link RunCredentialFeedback} and
 * {@code techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md}:
 * nothing in the shipped pipeline emits a credential-refusal cause, so both marks are operator-driven.
 * The rotation, the separation from the reviewer's key and the exhaustion refusal are all live; the
 * self-healing is not.
 */
@ApplicationScoped
public class HarnessCredentialPool {

    private static final Logger LOG = Logger.getLogger(HarnessCredentialPool.class);

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    /**
     * How long a member rests when the provider rate-limited it without saying for how long.
     *
     * <p>Bounded rather than indefinite: an unstated limit is still a promise that capacity returns,
     * and leaving the member out for ever would turn the recoverable state into the permanent one —
     * which is exactly the collapse the two states exist to prevent. An operator can shorten it by
     * clearing the member by hand.
     */
    @ConfigProperty(name = "spire.run.credential-rate-limit-default-seconds", defaultValue = "900")
    long defaultRateLimitSeconds;

    /**
     * One member with its key decrypted.
     *
     * <p>{@code toString} masks the key. A record prints every component, and
     * {@code LOG.debugf("selected %s", member)} is the obvious line for somebody to write — the same
     * reason {@code ExecuteRun} and {@code Credentials.Scm} mask theirs. A convention the type does
     * not enforce is one the type does not have.
     *
     * <p>{@code label}, {@code type} and {@code baseUrl} are operator METADATA: they name the key in
     * a vendor console and in this pool's own listing, and {@code baseUrl} is validated on the way in
     * like every sibling registry's. The dispatch path reads only {@code apiKey} — the endpoint the
     * agent calls is the harness image's, not this row's — so registering a member against a
     * self-hosted base URL does not change where the model call goes. Recorded in the javadoc because
     * the REST surface demands both fields, which reasonably implies they route something.
     */
    public record PoolMember(UUID id, String label, String type, String baseUrl, String apiKey) {

        @Override
        public String toString() {
            return "PoolMember[id=" + id + ", label=" + label + ", type=" + type
                    + ", baseUrl=" + baseUrl + ", apiKey=***]";
        }
    }

    /**
     * What the pool can offer, and when it cannot, WHY — because the three reasons need three
     * different people to do three different things.
     */
    public sealed interface Selection {

        /** A usable member. */
        record Chosen(PoolMember member) implements Selection {

            @Override
            public String toString() {
                return "Chosen[" + member + "]";
            }
        }

        /**
         * Nothing is available now, but a rate limit lifts at {@code capacityReturnsAt}.
         *
         * @param rejected how many members will NOT come back when it does. Carried because the wait
         *                 is only half the story: a pool of three with two refused and one resting
         *                 recovers to a single key, and an operator told only "retry then" waits for
         *                 members that are permanently dead. The attention row said this and the
         *                 dispatch refusal did not, which is the drift {@link PoolHealth} now closes.
         */
        record Resting(Instant capacityReturnsAt, int rejected) implements Selection {
        }

        /**
         * Every member was refused by its provider. Nothing returns without an operator: rotating
         * onto a rejected key spends a request per run to rediscover that it is dead.
         */
        record AllRejected(int count) implements Selection {
        }

        /** The pool has no enabled member at all — nothing is configured, or all are disabled. */
        record Empty() implements Selection {
        }
    }

    /**
     * Hand out the member that has rested longest, and record that it was used.
     *
     * <p>"Rested longest" rather than "least recently used" is the rotation this needs: a member that
     * was rate-limited an hour ago is a better bet than one that was rate-limited a minute ago, and a
     * member that has never been exhausted is the best bet of all. {@code last_used_at} then breaks
     * the tie, which is the ONLY ordering that applies to a healthy pool where nothing has ever been
     * exhausted — that is, to a working deployment most of the time.
     *
     * <p>Selection and the use stamp are ONE statement, so no caller can read a member and stamp it
     * in two steps and let a third dispatch interleave between them. It is deliberately NOT a lock:
     * two dispatches a millisecond apart may legitimately receive the same member, because a key is
     * not exclusive and serving two runs at once is what a pool is for. A probe confirms the spread
     * — eight concurrent sessions taking 320 members from a pool of four came out 80/80/80/80.
     *
     * <p>The statement is a WRITE, so callers must run every refusal they can before reaching it:
     * a request refused afterwards has consumed a rotation slot for a member it never used.
     *
     * <p><b>The ORDER BY and V52's partial index are one decision written twice, and only the index
     * is load-bearing.</b> Deleting {@code last_used_at NULLS FIRST} from this clause changes no
     * observable behaviour and no test can catch it, because the index carries that column as its
     * second key and Postgres returns rows in full index order anyway. Two reviews independently
     * failed to kill that mutation, which is worth recording so nobody hunts it a third time. What
     * IS asserted is the mechanism the rotation actually rests on — the use stamp above — and
     * removing that does fail a test. Change the index and this clause together.
     */
    public Selection select() {
        String sql = """
                UPDATE harness_credential
                   SET last_used_at = now(), updated_at = now()
                 WHERE id = (
                       SELECT id FROM harness_credential
                        WHERE enabled
                          AND rejected_at IS NULL
                          AND (rate_limited_until IS NULL OR rate_limited_until <= now())
                        ORDER BY exhausted_at NULLS FIRST, last_used_at NULLS FIRST
                        LIMIT 1)
                RETURNING id, label, type, base_url, api_key
                """;
        UUID chosen = null;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                chosen = rs.getObject("id", UUID.class);
                return new Selection.Chosen(decrypt(chosen, rs));
            }
        } catch (SQLException e) {
            // Not an empty pool: a read fault is not "you have no credentials", and answering Empty
            // would send an operator to configure keys they already have.
            throw new IllegalStateException("The harness credential pool could not be read", e);
        } catch (RuntimeException e) {
            // A key that cannot be DECRYPTED -- a rotated keyset, a row restored from a backup taken
            // under a different one, a broken AAD binding. EncryptionService throws
            // IllegalStateException, not SQLException, so this used to escape as a bare 500 with the
            // use stamp already committed: the member stayed in rotation and failed one dispatch in N
            // for ever, with nothing naming which member or why.
            //
            // Retired rather than merely reported, because the answer is the same as a provider
            // refusal: nothing but an operator brings it back.
            LOG.errorf(e, "harness credential %s could not be decrypted; it leaves the pool until an"
                    + " operator replaces it", chosen);
            markRejected(chosen);
            return whyNothingIsAvailable();
        }
        return whyNothingIsAvailable();
    }

    private PoolMember decrypt(UUID id, ResultSet rs) throws SQLException {
        return new PoolMember(id, rs.getString("label"), rs.getString("type"), rs.getString("base_url"),
                encryption.decryptString(rs.getString("api_key"), aad(id)));
    }

    /**
     * Nothing was selectable — say which of the three reasons, in the order that decides the action.
     *
     * <p>Rate limits first: if ANY member is resting, the pool recovers on its own and the honest
     * answer names the time AND how many will not return with it. Only when none is resting does
     * "every member is rejected" become the whole story.
     *
     * <p>The last branch is the transient race — a rate limit expiring, or a rejection being cleared,
     * between the selecting statement and this one. It answers {@code Resting(now)} rather than
     * {@code Empty}, because "no harness credential is configured" is precisely the wrong sentence
     * for an operator who has several, and it is the sentence this method's own design set out to
     * avoid.
     */
    private Selection whyNothingIsAvailable() {
        try (Connection c = dataSource.getConnection()) {
            PoolHealth health = PoolHealth.read(c);
            if (health.members() == 0) {
                return new Selection.Empty();
            }
            if (health.returnsAt() != null) {
                return new Selection.Resting(health.returnsAt(), health.rejected());
            }
            if (health.rejected() == health.members()) {
                return new Selection.AllRejected(health.rejected());
            }
            return new Selection.Resting(Instant.now(), health.rejected());
        } catch (SQLException e) {
            throw new IllegalStateException("The harness credential pool could not be read", e);
        }
    }

    /**
     * The provider rate-limited this member; it comes back on its own.
     *
     * <p>{@code until} is the provider's own answer when it gave one. A null becomes a bounded
     * default computed by the DATABASE's clock, not this process's: every other timestamp on the row
     * comes from {@code now()}, and a feature whose promise is "capacity returns at a stated time"
     * must not state it against a second clock.
     *
     * <p>{@code exhausted_at} is stamped whether or not the member is currently selectable, since it
     * is the rotation order rather than the availability test.
     *
     * @return whether a member was actually marked. False for an unknown id AND for an
     *         already-refused member, whose CHECK forbids carrying both states — a caller that
     *         reported success for either would assert a rest that never happened.
     */
    public boolean markRateLimited(UUID id, Instant until) {
        boolean marked = update("""
                UPDATE harness_credential
                   SET rate_limited_until = COALESCE(?, now() + make_interval(secs => ?)),
                       exhausted_at = now(), updated_at = now()
                 WHERE id = ? AND rejected_at IS NULL
                """, id, until == null ? null : Timestamp.from(until), (double) defaultRateLimitSeconds) == 1;
        if (marked) {
            LOG.warnf("harness credential %s is rate limited; the pool will rotate past it", id);
        }
        return marked;
    }

    /**
     * The provider refused this member. Nothing but an operator brings it back.
     *
     * <p>No expiry, on purpose. A key that was refused will be refused again, so a pool that retried
     * it would spend one request per run rediscovering that — and the runs it burns are paid ones.
     */
    public boolean markRejected(UUID id) {
        boolean marked = update("""
                UPDATE harness_credential
                   SET rejected_at = now(), rate_limited_until = NULL, exhausted_at = now(),
                       updated_at = now()
                 WHERE id = ? AND rejected_at IS NULL
                """, id) == 1;
        if (marked) {
            LOG.errorf("harness credential %s was refused and is out of the pool until an operator"
                    + " replaces or clears it", id);
        }
        return marked;
    }

    /** An operator says the key works again — a rotated secret, or restored credit. */
    public boolean clearRejection(UUID id) {
        return update("""
                UPDATE harness_credential
                   SET rejected_at = NULL, rate_limited_until = NULL, updated_at = now()
                 WHERE id = ? AND rejected_at IS NOT NULL
                """, id) == 1;
    }

    /** What the settings surface shows. Never carries the key. */
    public record MemberView(UUID id, String label, String type, String baseUrl, boolean enabled,
                             Instant rateLimitedUntil, Instant rejectedAt, Instant lastUsedAt) {
    }

    public List<MemberView> list() {
        String sql = """
                SELECT id, label, type, base_url, enabled, rate_limited_until, rejected_at, last_used_at
                  FROM harness_credential ORDER BY label
                """;
        List<MemberView> members = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                members.add(new MemberView(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getString("type"), rs.getString("base_url"), rs.getBoolean("enabled"),
                        instant(rs, "rate_limited_until"), instant(rs, "rejected_at"),
                        instant(rs, "last_used_at")));
            }
            return members;
        } catch (SQLException e) {
            throw new IllegalStateException("The harness credential pool could not be listed", e);
        }
    }

    /** Raised when a label is already taken, so the caller can answer 409 rather than 500. */
    public static class DuplicateLabelException extends IllegalStateException {
        DuplicateLabelException(String label, Throwable cause) {
            super("a harness credential named \"" + label + "\" already exists", cause);
        }
    }

    /**
     * Add a member. The key is Tink-encrypted with its AAD bound to the row, like every registry.
     *
     * @throws DuplicateLabelException when the label is taken. The unique label is load-bearing — the
     *         migration's own reasoning is that "which key is dead" is unanswerable when two are
     *         called the same — so the collision needs an answer an operator can act on.
     */
    public MemberView add(String label, String type, String baseUrl, String apiKey) {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO harness_credential (id, label, type, base_url, api_key)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, label);
            ps.setString(3, type);
            ps.setString(4, baseUrl);
            ps.setString(5, encryption.encryptString(apiKey, aad(id)));
            ps.executeUpdate();
            return new MemberView(id, label, type, baseUrl, true, null, null, null);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new DuplicateLabelException(label, e);
            }
            throw new IllegalStateException("The harness credential could not be added", e);
        }
    }

    /**
     * Take a member out of rotation, keeping the row.
     *
     * <p>Disabled rather than deleted because a {@code factory_run} row references it: the foreign
     * key has no {@code ON DELETE}, so a hard delete is REFUSED rather than cascaded, and the row a
     * finished run's attribution points at stays where it is.
     *
     * <p>The ciphertext stays too. Disabling is not revocation — a leaked key has to be revoked at
     * the vendor, and nothing here can do that.
     */
    public boolean remove(UUID id) {
        return update("UPDATE harness_credential SET enabled = FALSE, updated_at = now() WHERE id = ?",
                id) == 1;
    }

    /** Bring a disabled member back, because disabling is not deletion and must not be one-way. */
    public boolean enable(UUID id) {
        return update("UPDATE harness_credential SET enabled = TRUE, updated_at = now()"
                + " WHERE id = ? AND NOT enabled", id) == 1;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * Apply one statement whose id is bound LAST.
     *
     * <p>Every statement here ends in {@code WHERE id = ?}, so the id is named once and bound once.
     * It used to be passed twice — for the error message and again as a parameter — which a reader
     * had to open this method to discover, and whose failure mode is a runtime "no value specified
     * for parameter 1" in the code that decides which key is marked dead.
     */
    private int update(String sql, UUID id, Object... leadingParams) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < leadingParams.length; i++) {
                ps.setObject(i + 1, leadingParams[i]);
            }
            ps.setObject(leadingParams.length + 1, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The harness credential " + id + " could not be updated", e);
        }
    }

    private static String aad(UUID id) {
        return "harness-credential:" + id;
    }
}
