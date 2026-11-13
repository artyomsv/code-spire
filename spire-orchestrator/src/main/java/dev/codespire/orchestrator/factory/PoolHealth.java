package dev.codespire.orchestrator.factory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How much of the harness credential pool can serve a run right now.
 *
 * <p><b>One definition of "selectable", because three had already drifted.</b> The predicate lived in
 * the selector's {@code WHERE}, inverted across the diagnosis query's {@code FILTER} clauses, and
 * again in the attention row's own SQL in another file — and a review found the drift live rather
 * than hypothetical: the attention row correctly reported that a mixed pool contained permanently
 * refused members while the dispatch refusal told the same operator that every credential was merely
 * rate limited, and to retry. That is the shape {@code SpendGate} exists to prevent, arriving one
 * table over.
 *
 * @param members  enabled members, whatever their state
 * @param rejected enabled members the provider refused; nothing but an operator brings these back
 * @param resting  enabled members waiting out a rate limit; these return on their own
 * @param returnsAt the earliest moment a resting member becomes selectable, or null when none rests
 */
public record PoolHealth(int members, int rejected, int resting, Instant returnsAt) {

    /**
     * The one predicate. Everything that asks about this pool asks through this query.
     *
     * <p>{@code members = rejected + resting} is exactly the exhausted condition: a member that is
     * neither refused nor resting is selectable, so if the three agree there is nothing to hand out.
     * Deriving exhaustion that way rather than counting it separately is what keeps the selector and
     * every reader from disagreeing about what "exhausted" means.
     */
    private static final String SQL = """
            SELECT count(*) FILTER (WHERE enabled)                                   AS members,
                   count(*) FILTER (WHERE enabled AND rejected_at IS NOT NULL)       AS rejected,
                   count(*) FILTER (WHERE enabled AND rejected_at IS NULL
                                      AND rate_limited_until > now())                AS resting,
                   min(rate_limited_until) FILTER (WHERE enabled AND rejected_at IS NULL
                                      AND rate_limited_until > now())                AS returns_at
              FROM harness_credential
             -- The same boundary the selector draws: this explains why a RUN found nothing, so it must
             -- count exactly what a run could have been given.
             WHERE auth_mode = 'API_KEY'
               -- And the same kind: a run is paid only with a key of its harness's vendor.
               AND (CAST(? AS VARCHAR) IS NULL OR type = CAST(? AS VARCHAR))
            """;

    /**
     * The pool's health for one key kind ({@code harness_credential.type}), or for every API key when
     * the kind is empty.
     */
    public static PoolHealth read(Connection c, Optional<String> kind) throws SQLException {
        String type = kind.orElse(null);
        try (PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setString(1, type);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new PoolHealth(0, 0, 0, null);
                }
                Timestamp returnsAt = rs.getTimestamp("returns_at");
                return new PoolHealth(rs.getInt("members"), rs.getInt("rejected"), rs.getInt("resting"),
                        returnsAt == null ? null : returnsAt.toInstant());
            }
        }
    }

    /** The key kinds of the enabled API keys, so each can be read on its own. */
    public static List<String> kinds(Connection c) throws SQLException {
        List<String> kinds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT type FROM harness_credential
                 WHERE enabled AND auth_mode = 'API_KEY' ORDER BY type
                """); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kinds.add(rs.getString(1));
            }
        }
        return kinds;
    }

    /** Nothing can be handed out: every enabled member is either refused or resting. */
    public boolean exhausted() {
        return members > 0 && rejected + resting >= members;
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
    HarnessCredentialPool.Selection whyNothingIsAvailable() {
        if (members == 0) {
            return new HarnessCredentialPool.Selection.Empty();
        }
        if (returnsAt != null) {
            return new HarnessCredentialPool.Selection.Resting(returnsAt, rejected);
        }
        if (rejected == members) {
            return new HarnessCredentialPool.Selection.AllRejected(rejected);
        }
        return new HarnessCredentialPool.Selection.Resting(Instant.now(), rejected);
    }

    /**
     * The half of an exhausted pool that will never come back on its own, as a sentence fragment.
     *
     * <p>Empty when nothing is refused. Written once because both the dispatch refusal and the
     * attention row need it and only one of them used to say it — an operator told "every credential
     * is rate limited, retry then" would wait for keys that are permanently dead.
     */
    public String permanentlyLostHalf() {
        return rejected == 0 ? ""
                : " " + rejected + " of them were refused outright and will not come back without a new key.";
    }
}
