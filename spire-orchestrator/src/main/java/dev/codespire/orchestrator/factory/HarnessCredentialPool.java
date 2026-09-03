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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /** One member with its key decrypted. Never logged, never returned by the REST surface. */
    public record PoolMember(UUID id, String label, String type, String baseUrl, String apiKey) {
    }

    /**
     * What the pool can offer, and when it cannot, WHY — because the three reasons need three
     * different people to do three different things.
     */
    public sealed interface Selection {

        /** A usable member. */
        record Chosen(PoolMember member) implements Selection {
        }

        /**
         * Nothing is available now, but a rate limit lifts at {@code capacityReturnsAt}. The pool
         * heals itself; the operator's action is to wait, or to add another member.
         */
        record Resting(Instant capacityReturnsAt) implements Selection {
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
     * member that has never been exhausted is the best bet of all. Ordering by use alone would send
     * the next run straight back at the key that just ran out.
     *
     * <p>Selection and the use stamp are ONE statement. Read-then-update would let two dispatches a
     * millisecond apart both read the same longest-rested member and both take it, which is the
     * failure that makes a rotation policy look installed and do nothing under exactly the load it
     * exists for.
     *
     * <p>Two members serving two runs at once is fine and expected — a key is not exclusive. The
     * statement is about ORDER, not about locking.
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
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                return new Selection.Chosen(new PoolMember(id, rs.getString("label"),
                        rs.getString("type"), rs.getString("base_url"),
                        encryption.decryptString(rs.getString("api_key"), aad(id))));
            }
        } catch (SQLException e) {
            // Not an empty pool: a read fault is not "you have no credentials", and answering Empty
            // would send an operator to configure keys they already have. The caller refuses either
            // way, but it must refuse with the right sentence.
            throw new IllegalStateException("The harness credential pool could not be read", e);
        }
        return whyNothingIsAvailable();
    }

    /**
     * Nothing was selectable — say which of the three reasons, in the order that decides the action.
     *
     * <p>Rate limits first: if ANY member is resting, the pool recovers on its own and the honest
     * answer names the time. Only when none is resting does "every member is rejected" become the
     * whole story, and only when there is no enabled member at all is the answer "configure one".
     */
    private Selection whyNothingIsAvailable() {
        String sql = """
                SELECT count(*) FILTER (WHERE enabled)                                     AS enabled_members,
                       count(*) FILTER (WHERE enabled AND rejected_at IS NOT NULL)         AS rejected,
                       min(rate_limited_until) FILTER (WHERE enabled AND rejected_at IS NULL
                                                         AND rate_limited_until > now())   AS returns_at
                  FROM harness_credential
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || rs.getInt("enabled_members") == 0) {
                return new Selection.Empty();
            }
            Timestamp returnsAt = rs.getTimestamp("returns_at");
            if (returnsAt != null) {
                return new Selection.Resting(returnsAt.toInstant());
            }
            int rejected = rs.getInt("rejected");
            return rejected > 0 ? new Selection.AllRejected(rejected) : new Selection.Empty();
        } catch (SQLException e) {
            throw new IllegalStateException("The harness credential pool could not be read", e);
        }
    }

    /**
     * The provider rate-limited this member; it comes back on its own.
     *
     * <p>{@code until} is the provider's own answer when it gave one. A null becomes a bounded
     * default rather than an indefinite rest, because an unstated limit is still a promise.
     *
     * <p>{@code exhausted_at} is stamped whether or not the member is currently selectable, since it
     * is the rotation order rather than the availability test — a member that ran out an hour ago
     * should still sort behind one that never has.
     */
    public void markRateLimited(UUID id, Instant until) {
        Instant returnsAt = until != null ? until
                : Instant.now().plus(Duration.ofSeconds(defaultRateLimitSeconds));
        update("""
                UPDATE harness_credential
                   SET rate_limited_until = ?, exhausted_at = now(), updated_at = now()
                 WHERE id = ? AND rejected_at IS NULL
                """, id, Timestamp.from(returnsAt), id);
        LOG.warnf("harness credential %s is rate limited until %s; the pool will rotate past it", id, returnsAt);
    }

    /**
     * The provider refused this member. Nothing but an operator brings it back.
     *
     * <p>No expiry, on purpose. A key that was refused will be refused again, so a pool that retried
     * it would spend one request per run rediscovering that — and the runs it burns are paid ones.
     */
    public void markRejected(UUID id) {
        update("""
                UPDATE harness_credential
                   SET rejected_at = now(), rate_limited_until = NULL, exhausted_at = now(),
                       updated_at = now()
                 WHERE id = ? AND rejected_at IS NULL
                """, id, id);
        LOG.errorf("harness credential %s was refused by its provider and is out of the pool until an"
                + " operator replaces or clears it", id);
    }

    /** An operator says the key works again — a rotated secret, or restored credit. */
    public boolean clearRejection(UUID id) {
        return update("""
                UPDATE harness_credential
                   SET rejected_at = NULL, rate_limited_until = NULL, updated_at = now()
                 WHERE id = ? AND rejected_at IS NOT NULL
                """, id, id) == 1;
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

    /** Add a member. The key is Tink-encrypted with its AAD bound to the row, like every registry. */
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
            throw new IllegalStateException("The harness credential could not be added", e);
        }
    }

    public boolean remove(UUID id) {
        // The run rows keep pointing at it, so the FK is ON DELETE unset rather than CASCADE: a
        // deleted member must not take a finished run's attribution with it.
        return update("UPDATE harness_credential SET enabled = FALSE, updated_at = now() WHERE id = ?",
                id, id) == 1;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private int update(String sql, UUID id, Object... params) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The harness credential " + id + " could not be updated", e);
        }
    }

    private static String aad(UUID id) {
        return "harness-credential:" + id;
    }
}
