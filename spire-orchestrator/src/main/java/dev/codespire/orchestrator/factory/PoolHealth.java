package dev.codespire.orchestrator.factory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

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
            """;

    public static PoolHealth read(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new PoolHealth(0, 0, 0, null);
            }
            Timestamp returnsAt = rs.getTimestamp("returns_at");
            return new PoolHealth(rs.getInt("members"), rs.getInt("rejected"), rs.getInt("resting"),
                    returnsAt == null ? null : returnsAt.toInstant());
        }
    }

    /** Nothing can be handed out: every enabled member is either refused or resting. */
    public boolean exhausted() {
        return members > 0 && rejected + resting >= members;
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
