package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.PullRequestLander.MergeMethod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * How a repository lands a delivered pull request (M4 land, spec §7.1): the merge method GitHub is asked
 * for, what happens to the ticket afterwards, and how many agent fixes a refused merge may spend.
 *
 * <p>Every repository has an answer. No row means the operator's decisions (§6) — merge, keep the ticket
 * open with a comment, three attempts — so a repository set up before land existed lands the way the
 * operator decided, and the land effect never meets "not set". Values are stored and sent lower-case,
 * the words the table checks.
 */
@ApplicationScoped
public class LandSettings {
    @Inject DataSource dataSource;

    public static final int DEFAULT_FIX_ATTEMPTS = 3, MAX_FIX_ATTEMPTS = 10;

    /** COMMENT keeps the ticket's status and posts that the work is merged; CLOSE closes it. */
    public enum TicketRule { COMMENT, CLOSE }

    /** @param revision 0 when the repository has no row, with the defaults filled in and no saver */
    public record Settings(long revision, MergeMethod mergeMethod, TicketRule ticketRule, int fixAttempts,
                           String updatedBy, Instant updatedAt) {
        public static Settings defaults() {
            return new Settings(0, MergeMethod.MERGE, TicketRule.COMMENT, DEFAULT_FIX_ATTEMPTS, null, null);
        }
    }

    /** @param fixAttempts boxed so an omitted value is refused rather than read as 0, "hand me everything" */
    public record Input(long expectedRevision, String mergeMethod, String ticketRule, Integer fixAttempts) {}

    /** A refusal that names its rule, so the screen can say what to change rather than "400". */
    public static final class Refused extends RuntimeException {
        private final String reason;
        Refused(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public Settings get(UUID repository) {
        try (Connection c = dataSource.getConnection()) { return get(c, repository, false); }
        catch (SQLException failure) { throw database(failure); }
    }

    /** On the caller's connection, so the land effect can read the rule inside its own transaction. */
    public Settings get(Connection c, UUID repository, boolean lock) throws SQLException {
        String sql = "SELECT revision,merge_method,ticket_rule,fix_attempts,updated_by,updated_at"
                + " FROM repository_land_settings WHERE repository_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Settings.defaults();
                return new Settings(rs.getLong(1), parse(MergeMethod.class, rs.getString(2)),
                        parse(TicketRule.class, rs.getString(3)), rs.getInt(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant());
            }
        }
    }

    /**
     * Transactional for the reason {@code BuildDefaults.save} gives: without it the {@code FOR UPDATE}
     * locks are released as soon as they are taken, and two operators who both read revision N would
     * both write, the second silently overwriting the first.
     */
    @Transactional
    public Settings save(UUID repository, Input input, String subject) {
        if (input == null) throw new Refused("land_settings_required");
        if (subject == null || subject.isBlank()) throw new Refused("operator_identity_required");
        MergeMethod method = parse(MergeMethod.class, input.mergeMethod());
        if (method == null) throw new Refused("merge_method_invalid");
        TicketRule rule = parse(TicketRule.class, input.ticketRule());
        if (rule == null) throw new Refused("ticket_rule_invalid");
        Integer attempts = input.fixAttempts();
        // Checked here and not only by the table, so the screen gets the rule instead of a constraint violation.
        if (attempts == null || attempts < 0 || attempts > MAX_FIX_ATTEMPTS) throw new Refused("fix_attempts_invalid");
        try (Connection c = dataSource.getConnection()) {
            // The repository row first, the same lock order as the build setup and the policy save.
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM repository WHERE id=? FOR UPDATE")) {
                ps.setObject(1, repository);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new Refused("repository_unknown"); }
            }
            get(c, repository, true);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO repository_land_settings(repository_id,merge_method,ticket_rule,fix_attempts,updated_by)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (repository_id) DO UPDATE SET merge_method=excluded.merge_method,
                        ticket_rule=excluded.ticket_rule,fix_attempts=excluded.fix_attempts,
                        updated_by=excluded.updated_by,updated_at=now(),
                        revision=repository_land_settings.revision+1
                    """)) {
                ps.setObject(1, repository); ps.setString(2, wire(method)); ps.setString(3, wire(rule));
                ps.setInt(4, attempts); ps.setString(5, subject);
                ps.executeUpdate();
            }
            return get(c, repository, false);
        } catch (SQLException failure) { throw database(failure); }
    }

    /** The lower-case word the table stores and the screen sends. */
    public static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Exactly one of the lower-case words, or null: no case folding, no trimming of a typed value. */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) return null;
        for (E constant : type.getEnumConstants()) if (wire(constant).equals(value)) return constant;
        return null;
    }

    private static IllegalStateException database(SQLException failure) {
        return new IllegalStateException("The repository land settings could not be read or saved", failure);
    }
}
