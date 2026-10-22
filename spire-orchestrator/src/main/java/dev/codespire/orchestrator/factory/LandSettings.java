package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.PullRequestLander.MergeMethod;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** RED placeholder for the per-repository land settings. */
@ApplicationScoped
public class LandSettings {
    public enum TicketRule { COMMENT, CLOSE }

    public record Settings(long revision, MergeMethod mergeMethod, TicketRule ticketRule, int fixAttempts,
                           String updatedBy, Instant updatedAt) {}

    public record Input(long expectedRevision, String mergeMethod, String ticketRule, Integer fixAttempts) {}

    public static final class Refused extends RuntimeException {
        private final String reason;
        Refused(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public Settings get(UUID repository) {
        return new Settings(0, MergeMethod.SQUASH, TicketRule.CLOSE, 0, null, null);
    }

    public Settings get(Connection c, UUID repository, boolean lock) throws SQLException {
        return get(repository);
    }

    public Settings save(UUID repository, Input input, String subject) {
        throw new UnsupportedOperationException("RED");
    }
}
