package dev.codespire.orchestrator.attention;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The acknowledgement watermark for the two ledger-wide cost conditions — read when the panel counts
 * them, written when an operator dismisses one.
 *
 * <p>Stored in {@code app_setting} rather than a column of its own: it is one global timestamp per
 * condition, exactly what that key/value table exists for, and it needs no migration. The per-review
 * rows use {@code review_status.attention_ack_at} for the same job because their acknowledgement is
 * per record.
 */
@ApplicationScoped
class CostAttentionAcks {

    private static final Logger LOG = Logger.getLogger(CostAttentionAcks.class);

    @Inject
    AppSettingRepository settings;

    /**
     * Count calls priced after this instant. {@link Instant#EPOCH} when the condition has never been
     * acknowledged, which makes the predicate uniform — one query shape, no conditional SQL.
     *
     * <p>An unparseable stored value also reads as EPOCH: showing the row is the honest direction, since
     * the alternative would silence a money-accuracy warning on the strength of a value nobody can read.
     */
    Instant since(CostAttentionRow row) {
        return settings.get(row.ackKey())
                .map(value -> parse(row, value))
                .orElse(Instant.EPOCH);
    }

    void acknowledge(CostAttentionRow row) {
        settings.set(row.ackKey(), Instant.now().toString());
    }

    private static Instant parse(CostAttentionRow row, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            LOG.warnf("Ignoring an unreadable acknowledgement for %s ('%s') — reporting every call",
                    row, value);
            return Instant.EPOCH;
        }
    }
}
